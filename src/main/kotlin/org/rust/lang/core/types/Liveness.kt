/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.infer.MutabilityCategory
import java.util.*

data class LivenessAnalysisResult(
    val unusedVariables: List<RsElement>,
    val unusedArguments: List<RsElement>,
    val deadAssignments: List<RsElement>
)

class LivenessContext private constructor(
    val owner: RsInferenceContextOwner,
    val body: RsBlock,
    val implLookup: ImplLookup = ImplLookup.relativeTo(body),
    private val unusedVariables: MutableList<RsElement> = mutableListOf(),
    private val unusedArguments: MutableList<RsElement> = mutableListOf(),
    private val deadAssignments: MutableList<RsElement> = mutableListOf()
) {
    fun reportUnusedVariable(element: RsElement) {
        unusedVariables.add(element)
    }

    fun reportUnusedArgument(element: RsElement) {
        unusedArguments.add(element)
    }

    fun reportDeadAssignment(element: RsElement) {
        deadAssignments.add(element)
    }

    fun check(): LivenessAnalysisResult? {
        val cfg = owner.controlFlowGraph ?: return null
        val livenessContext = GatherLivenessContext(this)
        val livenessData = livenessContext.gather()
        val flowedLiveness = FlowedLivenessData.buildFor(livenessData, cfg)
        val checkLiveness = CheckLiveness(this, flowedLiveness)
        checkLiveness.check()
        return LivenessAnalysisResult(unusedVariables, unusedArguments, deadAssignments)
    }

    companion object {
        fun buildFor(owner: RsInferenceContextOwner): LivenessContext? {
            // TODO: handle body represented by RsExpr
            val body = owner.body as? RsBlock ?: return null
            return LivenessContext(owner, body)
        }
    }
}

object LiveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // liveness from both preds are in scope
    override val initialValue: Boolean get() = false                // dead by default
}

typealias LivenessDataFlow = DataFlowContext<LiveDataFlowOperator>

class FlowedLivenessData(
    val livenessData: LivenessData,
    val dfcxLiveness: LivenessDataFlow
) {
    fun isPathUsed(usagePath: UsagePath): Boolean {
        val basePaths = livenessData.existingBasePaths(usagePath)

        // Good scenarios:
        // 1. Assign to `a.b.c`, use of `a.b.c`
        // 2. Assign to `a.b.c`, use of `a` or `a.b`
        // 3. Assign to `a.b.c`, use of `a.b.c.d`
        //
        // Bad scenario:
        // 4. Assign to `a.b.c`, use of `a.b.d`
        var isDead = true
        dfcxLiveness.eachBitAtFlowExit { index ->
            val path = livenessData.paths[index]
            // Scenario 1 or 2: `usagePath` or some base path of `usagePath` was used
            if (basePaths.any { it == path }) {
                isDead = false
            } else {
                // Scenario 3: some extension of `loanPath` was moved
                val eachBasePathIsNotUsed = !livenessData.eachBasePath(path) { it != path }
                if (!eachBasePathIsNotUsed) isDead = false
            }
            isDead
        }
        return !isDead
    }

    companion object {
        fun buildFor(livenessData: LivenessData, cfg: ControlFlowGraph): FlowedLivenessData {
            val dfcxLiveness = DataFlowContext(cfg, LiveDataFlowOperator, livenessData.paths.size)

            livenessData.addGenKills(dfcxLiveness)
            dfcxLiveness.addKillsFromFlowExits()
            dfcxLiveness.propagate()

            return FlowedLivenessData(livenessData, dfcxLiveness)
        }
    }
}

class GatherLivenessContext(
    val ctx: LivenessContext,
    val livenessData: LivenessData = LivenessData()
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        val path = livenessData.usagePathOf(cmt) ?: return
        livenessData.addUsage(path, element)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {}

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        pat.descendantsOfType<RsPatBinding>().forEach { binding ->
            val kind = UsagePathKind.Var(binding)
            val path = livenessData.usagePathOf(kind) ?: return
            livenessData.addDeclaration(path, binding)
        }
        val path = livenessData.usagePathOf(cmt) ?: return
        livenessData.addUsage(path, pat)
    }

    override fun declarationWithoutInit(element: RsElement) {
        val kind = UsagePathKind.Var(element)
        val path = livenessData.usagePathOf(kind) ?: return
        livenessData.addDeclaration(path, element)
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        val path = livenessData.usagePathOf(assigneeCmt) ?: return
        when (mode) {
            MutateMode.Init -> { /* livenessData.addDeclaration(path, assignmentElement) */
            }
            MutateMode.JustWrite -> livenessData.addAssignment(path, assignmentElement)
            MutateMode.WriteAndRead -> {
                livenessData.addUsage(path, assignmentElement)
                livenessData.addAssignment(path, assignmentElement)
            }
        }
    }

    fun gather(): LivenessData {
        val gatherVisitor = ExprUseWalker(this, MemoryCategorizationContext(ctx.implLookup, ctx.owner.inference))
        gatherVisitor.consumeBody(ctx.body)
        return livenessData
    }
}

class CheckLiveness(
    val ctx: LivenessContext,
    val flowedLivenessData: FlowedLivenessData
) {
    fun check() {
        for (path in flowedLivenessData.livenessData.paths) {
            if (flowedLivenessData.isPathUsed(path)) continue
            val kind = path.kind
            if (kind is UsagePathKind.Var) {
                val declaration = kind.declaration
                if (declaration.ancestorOrSelf<RsLetDecl>() != null) {
                    ctx.reportUnusedVariable(kind.declaration)
                } else if (declaration.ancestorOrSelf<RsValueParameter>() != null) {
                    ctx.reportUnusedArgument(kind.declaration)
                }
            }
        }
    }
}

class UsagePath(
    val kind: UsagePathKind,
    val index: Int,
    var parent: UsagePath? = null,
    var firstChild: UsagePath? = null,
    var nextSibling: UsagePath? = null
) {
    override fun equals(other: Any?): Boolean =
        Objects.equals(this.kind, (other as? UsagePath)?.kind)

    override fun hashCode(): Int =
        kind.hashCode()

    /*
    val isVariablePath: Boolean
        get() = parent == null

    val isPrecise: Boolean
        get() = when (kind) {
            is UsagePathKind.Var -> true
            is UsagePathKind.Extend -> kind.isInterior
        }
     */
}

sealed class UsagePathKind {
    /** [Var] kind relates to [Categorization.Local] memory category */
    data class Var(val declaration: RsElement) : UsagePathKind()

    /** [Extend] kind relates to [[Categorization.Deref] and [Categorization.Interior] memory categories */
    data class Extend(
        val baseKind: UsagePathKind,
        val mutCategory: MutabilityCategory,
        val isInterior: Boolean
    ) : UsagePathKind()

    companion object {
        fun computeFor(cmt: Cmt): UsagePathKind? {
            val category = cmt.category
            return when (category) {
                is Categorization.Rvalue -> {
                    val declaration = (cmt.element as? RsExpr)?.declaration ?: return null
                    if (declaration is RsItemElement) return null
                    return Var(declaration)
                }

                // TODO
                is Categorization.StaticItem -> null

                is Categorization.Local -> Var(category.declaration)

                is Categorization.Deref -> {
                    val baseKind = computeFor(category.cmt) ?: return null
                    Extend(baseKind, cmt.mutabilityCategory, false)
                }

                is Categorization.Interior -> {
                    val baseCmt = category.cmt
                    val baseKind = computeFor(baseCmt) ?: return null
                    Extend(baseKind, cmt.mutabilityCategory, true)
                }

                else -> null
            }
        }
    }
}

data class Usage(val path: UsagePath, val element: RsElement)
data class Declaration(val path: UsagePath, val element: RsElement)
data class Assignment(val path: UsagePath, val element: RsElement)

class LivenessData(
    val usages: MutableList<Usage> = mutableListOf(),
    val declarations: MutableList<Declaration> = mutableListOf(),
    val assignments: MutableList<Assignment> = mutableListOf(),
    val paths: MutableList<UsagePath> = mutableListOf(),
    val pathMap: MutableMap<UsagePathKind, UsagePath> = mutableMapOf()
) {
    fun usagePathOf(cmt: Cmt): UsagePath? =
        UsagePathKind.computeFor(cmt)?.let { usagePathOf(it) }

    fun usagePathOf(kind: UsagePathKind): UsagePath? {
        pathMap[kind]?.let { return it }

        val oldSize = when (kind) {
            is UsagePathKind.Var -> {
                val index = paths.size
                paths.add(UsagePath(kind, index))
                index
            }

            is UsagePathKind.Extend -> {
                val index = paths.size
                val baseKind = kind.baseKind
                val parentPath = usagePathOf(baseKind)!!
                val newMovePath = UsagePath(kind, index, parentPath, null, parentPath.firstChild)
                parentPath.firstChild = newMovePath
                paths.add(newMovePath)
                index
            }
        }

        // testAssert { oldSize == paths.size - 1 }
        pathMap[kind] = paths.last()
        return paths.last()
    }

    fun existingBasePaths(usagePath: UsagePath): List<UsagePath> {
        val result = mutableListOf<UsagePath>()
        eachBasePath(usagePath) { result.add(it) }
        return result
    }

    fun eachBasePath(usagePath: UsagePath, predicate: (UsagePath) -> Boolean): Boolean {
        var path = usagePath
        while (true) {
            if (!predicate(path)) return false
            path = path.parent ?: return true
        }
    }

    fun addGenKills(dfcxLiveness: LivenessDataFlow) {
        usages.forEach { usage ->
            dfcxLiveness.addGen(usage.element, usage.path.index)
        }

        declarations.forEach { declaration ->
            dfcxLiveness.addKill(KillFrom.ScopeEnd, declaration.element, declaration.path.index)
        }

        assignments.forEach { assignment ->
            dfcxLiveness.addKill(KillFrom.Execution, assignment.element, assignment.path.index)
        }
    }

    fun addUsage(usagePath: UsagePath, element: RsElement) {
        usages.add(Usage(usagePath, element))
    }

    fun addAssignment(usagePath: UsagePath, element: RsElement) {
        assignments.add(Assignment(usagePath, element))
    }

    fun addDeclaration(usagePath: UsagePath, element: RsElement) {
        declarations.add(Declaration(usagePath, element))
    }
}
