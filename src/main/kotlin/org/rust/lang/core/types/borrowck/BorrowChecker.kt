/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.ControlFlowGraph
import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.bodyOwnedBy
import org.rust.lang.core.types.borrowck.LoanPathElement.Deref
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.borrowck.gatherLoans.gatherLoansInFn
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.infer.MutabilityCategory
import org.rust.lang.core.types.infer.PointerKind
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.regions.ScopeTree
import org.rust.lang.core.types.regions.getRegionScopeTree
import org.rust.lang.core.types.ty.Ty

object LoanDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred
    override val initialValue: Boolean = false
}

typealias LoanDataFlow = DataFlowContext<LoanDataFlowOperator>

class AnalysisData(val allLoans: List<Loan>, val loans: LoanDataFlow, val moveData: FlowedMoveData)

class Loan(val index: Int,
           val loanPath: LoanPath,
           val kind: BorrowKind,
           val restrictedPaths: MutableList<LoanPath>,
           val genScope: Scope,  // where loan is introduced
           val killScope: Scope, // where the loan goes out of scope
           val cause: LoanCause
)

data class LoanPath(val kind: LoanPathKind, val ty: Ty) {
    val hasDowncast: Boolean
        get() = when {
            kind is Downcast -> true
            kind is Extend && kind.lpElement is Interior -> kind.loanPath.hasDowncast
            else -> false
        }

    fun killScope(bccx: BorrowCheckContext): Scope =
        when (kind) {
            is Var -> bccx.regionScopeTree.getVariableScope(kind.element)
            is Upvar -> throw NotImplementedError() // TODO
            is Downcast -> kind.loanPath.killScope(bccx)
            is Extend -> kind.loanPath.killScope(bccx)
        }

    fun hasFork(other: LoanPath): Boolean {
        val thisKind = this.kind
        val otherKind = other.kind
        return when {
            thisKind is Extend && otherKind is Extend && thisKind.lpElement is Interior && otherKind.lpElement is Interior ->
                if (thisKind.lpElement == otherKind.lpElement) {
                    thisKind.loanPath.hasFork(otherKind.loanPath)
                } else {
                    true
                }

            thisKind is Extend && thisKind.lpElement is Deref -> thisKind.loanPath.hasFork(other)

            otherKind is Extend && otherKind.lpElement is Deref -> this.hasFork(otherKind.loanPath)

            else -> false
        }
    }
}

sealed class LoanPathKind {
    class Var(val element: RsElement) : LoanPathKind()
    class Upvar : LoanPathKind()
    class Downcast(val loanPath: LoanPath, val element: RsElement) : LoanPathKind()
    class Extend(val loanPath: LoanPath, val mutCategory: MutabilityCategory, val lpElement: LoanPathElement) : LoanPathKind()
}

sealed class LoanPathElement {
    data class Deref(val kind: PointerKind) : LoanPathElement()
    data class Interior(val element: RsElement?, val kind: InteriorKind) : LoanPathElement()
}

enum class LoanCause {
    ClosureCapture,
    AddrOf,
    AutoRef,
    AutoUnsafe,
    RefBinding,
    OverloadedOperator,
    ClosureInvocation,
    ForLoop,
    MatchDiscriminant
}

class BorrowCheckResult(val usedMutNodes: MutableSet<RsElement>)

class BorrowCheckContext(
    val regionScopeTree: ScopeTree,
    val owner: RsElement,
    val body: RsBlock,
    val usedMutNodes: MutableSet<RsElement> = mutableSetOf()
)

fun borrowck(owner: RsElement): BorrowCheckResult? {
    val body = owner.bodyOwnedBy ?: return null
    val regoionScopeTree = getRegionScopeTree(owner)
    val bccx = BorrowCheckContext(regoionScopeTree, owner, body)

    val data = buildBorrowckDataflowData(bccx, false, body)
    if (data != null) {
        checkLoans(bccx, data.loans, data.moveData, data.allLoans, body)
        // TODO: implement and call `unusedCheck(borrowCheckContext, body)`
    }

    return BorrowCheckResult(bccx.usedMutNodes)
}

fun buildBorrowckDataflowData(bccx: BorrowCheckContext, forceAnalysis: Boolean, body: RsBlock): AnalysisData? {
    val (allLoans, moveData) = gatherLoansInFn(bccx, body)
    if (!forceAnalysis && allLoans.isEmpty() && moveData.isEmpty()) return null

    val cfg = ControlFlowGraph(body)
    val loanDfcx = DataFlowContext("borrowck", body, cfg, LoanDataFlowOperator, allLoans.size)

    allLoans.forEachIndexed { i, loan ->
        loanDfcx.addGen(loan.genScope.element, i)
        loanDfcx.addKill(KillFrom.ScopeEnd, loan.killScope.element, i)
    }
    loanDfcx.addKillsFromFlowExits()
    loanDfcx.propagate()

    val flowedMoves = FlowedMoveData(moveData, bccx, cfg, body)
    return AnalysisData(allLoans, loanDfcx, flowedMoves)
}
