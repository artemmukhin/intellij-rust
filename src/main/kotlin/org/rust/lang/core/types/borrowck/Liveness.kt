/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.RsValueParameter
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.infer.MutabilityCategory
import org.rust.lang.core.types.inference
import org.rust.openapiext.testAssert
import java.util.*


object LiveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // liveness from both preds are in scope
    override val initialValue: Boolean get() = false                // dead by default
}

typealias LivenessDataFlow = DataFlowContext<LiveDataFlowOperator>

class FlowedLivenessData(
    private val livenessData: LivenessData,
    private val dfcxLiveness: LivenessDataFlow
) {
    fun check(bccx: BorrowCheckContext) {
        // TODO: bccx.reportUnusedVariable(var)
    }

    companion object {
        fun buildFor(livenessData: LivenessData, cfg: ControlFlowGraph): FlowedLivenessData {
            val dfcxLiveness = DataFlowContext(cfg, LiveDataFlowOperator, livenessData.usages.size)

            livenessData.addGenKills(dfcxLiveness)
            dfcxLiveness.addKillsFromFlowExits()
            dfcxLiveness.propagate()

            return FlowedLivenessData(livenessData, dfcxLiveness)
        }
    }
}

class LivenessContext(val bccx: BorrowCheckContext, val livenessData: LivenessData = LivenessData()) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        val usagePath = livenessData.usagePathOf(cmt) ?: return
        livenessData.addUsage(usagePath, element)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {
        TODO("not implemented")
    }

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        TODO("not implemented")
    }

    override fun declarationWithoutInit(element: RsElement) {
        TODO("not implemented")
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        TODO("not implemented")
    }

    fun check(): LivenessData {
        val visitor = ExprUseWalker(this, MemoryCategorizationContext(bccx.implLookup, bccx.owner.inference))
        visitor.consumeBody(bccx.body)
        return livenessData
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

    val isVariablePath: Boolean
        get() = parent == null

    val isPrecise: Boolean
        get() = when (kind) {
            is UsagePathKind.Var -> true
            is UsagePathKind.Extend -> kind.interior == null
        }
}

class Usage(val path: UsagePath, val element: RsElement)

sealed class UsagePathKind {
    /** [Var] kind relates to [Categorization.Local] memory category */
    data class Var(val declaration: RsElement) : UsagePathKind()

    /** [Extend] kind relates to [[Categorization.Deref] and [Categorization.Interior] memory categories */
    data class Extend(val baseKind: UsagePathKind, val mutCategory: MutabilityCategory, val interior: Categorization.Interior?) : UsagePathKind()

    companion object {
        fun computeFor(cmt: Cmt): UsagePathKind? {
            val category = cmt.category
            return when (category) {
                is Categorization.Rvalue, Categorization.StaticItem -> null

                is Categorization.Local -> UsagePathKind.Var(category.declaration)

                is Categorization.Deref -> {
                    val baseKind = computeFor(category.cmt) ?: return null
                    UsagePathKind.Extend(baseKind, cmt.mutabilityCategory, null)
                }

                is Categorization.Interior -> {
                    val baseCmt = category.cmt
                    val baseKind = computeFor(baseCmt) ?: return null
                    UsagePathKind.Extend(baseKind, cmt.mutabilityCategory, category)
                }

                else -> null
            }
        }
    }
}

class LivenessData(
    val usages: MutableList<Usage> = mutableListOf(),
    // TODO
//    val declarations: MutableList<RsLetDecl> = mutableListOf(),
    val arguments: MutableMap<RsValueParameter, UsagePath> = mutableMapOf(),
    val paths: MutableList<UsagePath> = mutableListOf(),
    val pathMap: MutableMap<UsagePathKind, UsagePath> = mutableMapOf()
) {


    fun usagePathOf(cmt: Cmt): UsagePath? {
        val kind = UsagePathKind.computeFor(cmt) ?: return null
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

    private fun eachExtendingPath(usagePath: UsagePath, action: (UsagePath) -> Boolean): Boolean {
        if (!action(usagePath)) return false
        var path = usagePath.firstChild
        while (path != null) {
            if (!eachExtendingPath(path, action)) return false
            path = path.nextSibling
        }
        return true
    }

    private fun kill(usagePath: UsagePath, killElement: RsElement, dfcxLiveness: LivenessDataFlow) {
        if (!usagePath.isPrecise) return

        eachExtendingPath(usagePath) { path ->
            dfcxLiveness.addKill(KillFrom.Execution, killElement, path.index)
            true
        }
    }

    fun addGenKills(dfcxLiveness: LivenessDataFlow) {
        usages.forEach { usage ->
            dfcxLiveness.addGen(usage.element, usage.path.index)
        }

        // TODO: kill arguments and local variables
//        paths.forEach { path ->
//            kill(path, path.declaration, dfcxLiveness)
//        }
    }

    fun addUsage(usagePath: UsagePath, element: RsElement) {
        usages.add(Usage(usagePath, element))
    }
}
