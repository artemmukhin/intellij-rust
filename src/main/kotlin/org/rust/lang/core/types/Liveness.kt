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
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.infer.MutabilityCategory
import org.rust.openapiext.testAssert
import java.util.*


object LiveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // liveness from both preds are in scope
    override val initialValue: Boolean get() = false                // dead by default
}

typealias LivenessDataFlow = DataFlowContext<LiveDataFlowOperator>

class FlowedLivenessData(
    val livenessData: LivenessData,
    val dfcxLiveness: LivenessDataFlow
) {
    fun isPathUsed(element: RsElement, usagePath: UsagePath): Boolean {
        val baseNodes = livenessData.existingBasePaths(usagePath)

        // Good scenarios:
        // 1. Assign to `a.b.c`, use of `a.b.c`
        // 2. Assign to `a.b.c`, use of `a` or `a.b`
        // 3. Assign to `a.b.c`, use of `a.b.c.d`
        //
        // Bad scenario:
        // 4. Assign to `a.b.c`, use of `a.b.d`
        var isNotUsed = true
        // TODO: entry or exit?
        dfcxLiveness.eachBitOnEntry(element) { index ->
            val usage = livenessData.usages[index]
            val path = usage.path
            // Scenario 1 or 2: `usagePath` or some base path of `usagePath` was used
            if (baseNodes.any { it == path }) {
                isNotUsed = false
            } else {
                // Scenario 3: some extension of `loanPath` was moved
                val eachBasePathIsNotUsed = !livenessData.eachBasePath(path) { it != path }
                if (!eachBasePathIsNotUsed) isNotUsed = false
            }
            isNotUsed
        }
        return !isNotUsed
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
    val bccx: BorrowCheckContext,
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
        val gatherVisitor = ExprUseWalker(this, MemoryCategorizationContext(bccx.implLookup, bccx.owner.inference))
        gatherVisitor.consumeBody(bccx.body)
        return livenessData
    }
}

class CheckLiveness(
    val bccx: BorrowCheckContext,
    val flowedLivenessData: FlowedLivenessData
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {
    }

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
    }

    override fun declarationWithoutInit(element: RsElement) {
        //
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        val path = flowedLivenessData.livenessData.usagePathOf(assigneeCmt) ?: return
        val isUsed = flowedLivenessData.isPathUsed(assignmentElement, path)

        if (!isUsed) {
            val kind = path.kind
            when (mode) {
                MutateMode.Init -> if (kind is UsagePathKind.Var) {
                    val declaration = kind.declaration
                    if (declaration.ancestorOrSelf<RsLetDecl>() != null) {
                        bccx.reportUnusedVariable(kind.declaration)
                    } else if (declaration.ancestorOrSelf<RsValueParameter>() != null) {
                        bccx.reportUnusedArgument(kind.declaration)
                    }
                }
                MutateMode.JustWrite -> bccx.reportDeadAssignment(assignmentElement)
                MutateMode.WriteAndRead -> bccx.reportDeadAssignment(assignmentElement)
            }
        }
    }

    fun check(bccx: BorrowCheckContext) {
        val checkVisitor = ExprUseWalker(this, MemoryCategorizationContext(bccx.implLookup, bccx.owner.inference))
        checkVisitor.consumeBody(bccx.body)
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
//    val arguments: MutableList<Argument> = mutableListOf(),
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
                val parentPath = pathMap[baseKind]!!
                val newMovePath = UsagePath(kind, index, parentPath, null, parentPath.firstChild)
                parentPath.firstChild = newMovePath
                paths.add(newMovePath)
                index
            }
        }

        testAssert { oldSize == paths.size - 1 }
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
        usages.forEachIndexed { i, usage ->
            dfcxLiveness.addGen(usage.element, i)
        }

        declarations.forEachIndexed { i, declaration ->
            dfcxLiveness.addKill(KillFrom.ScopeEnd, declaration.element, i)
        }

        assignments.forEachIndexed { i, assignment ->
            dfcxLiveness.addKill(KillFrom.Execution, assignment.element, i)
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
