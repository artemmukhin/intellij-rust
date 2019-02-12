/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsInferenceContextOwner
import org.rust.lang.core.psi.ext.body
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.types.borrowck.gatherLoans.GatherLoanContext
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.regions.ScopeTree
import org.rust.lang.core.types.regions.getRegionScopeTree

class BorrowCheckContext(
    val regionScopeTree: ScopeTree,
    val owner: RsInferenceContextOwner,
    val body: RsBlock,
    val implLookup: ImplLookup = ImplLookup.relativeTo(body),
    private val usesOfMovedValue: MutableList<UseOfMovedValueError> = mutableListOf(),
    private val usesOfUninitializedVariable: MutableList<UseOfUninitializedVariable> = mutableListOf(),
    private val moveErrors: MutableList<MoveError> = mutableListOf(),
    private val unusedVariables: MutableList<RsElement> = mutableListOf()
) {
    companion object {
        fun buildFor(owner: RsInferenceContextOwner): BorrowCheckContext? {
            // TODO: handle body represented by RsExpr
            val body = owner.body as? RsBlock ?: return null
            val regionScopeTree = getRegionScopeTree(owner)
            return BorrowCheckContext(regionScopeTree, owner, body)
        }
    }

    fun check(): BorrowCheckResult? {
        val data = buildAnalysisData(this)
        if (data != null) {
            val clcx = CheckLoanContext(this, data.moveData)
            clcx.checkLoans(body)
            data.flowedLiveness.check(this)
        }
        return BorrowCheckResult(usesOfMovedValue, usesOfUninitializedVariable, moveErrors, unusedVariables)
    }

    private fun buildAnalysisData(bccx: BorrowCheckContext): AnalysisData? {
        val glcx = GatherLoanContext(this)
        val moveData = glcx.check().takeIf { it.isNotEmpty() } ?: return null

        val livenessContext = LivenessContext(this)
        val livenessData = livenessContext.check()

        val cfg = ControlFlowGraph.buildFor(bccx.body)
        val flowedMoves = FlowedMoveData.buildFor(moveData, bccx, cfg)
        val flowedLiveness = FlowedLivenessData.buildFor(livenessData, cfg)
        return AnalysisData(flowedMoves, flowedLiveness)
    }

    fun reportUseOfMovedValue(loanPath: LoanPath, move: Move) {
        if (move.kind == MoveKind.Declared) {
            usesOfUninitializedVariable.add(UseOfUninitializedVariable(loanPath.element))
        } else {
            usesOfMovedValue.add(UseOfMovedValueError(loanPath.element, move))
        }
    }

    fun reportMoveError(from: Cmt, to: MovePlace?) {
        moveErrors.add(MoveError(from, to))
    }

    fun reportUnusedVariable(element: RsElement) {
        unusedVariables.add(element)
    }
}

class AnalysisData(val moveData: FlowedMoveData, val flowedLiveness: FlowedLivenessData)

data class BorrowCheckResult(
    val usesOfMovedValue: List<UseOfMovedValueError>,
    val usesOfUninitializedVariable: List<UseOfUninitializedVariable>,
    val moveErrors: List<MoveError>,
    val unusedVariables: MutableList<RsElement>
)

class UseOfMovedValueError(val use: RsElement, val move: Move)
class UseOfUninitializedVariable(val use: RsElement)
class MoveError(val from: Cmt, val to: MovePlace?)
