/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.types.borrowck.MovedValueUseKind.MovedInCapture
import org.rust.lang.core.types.borrowck.MovedValueUseKind.MovedInUse
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope
import org.rust.openapiext.testAssert

fun checkLoans(
    bccx: BorrowCheckContext,
    dfcxLoans: LoanDataFlow,
    moveData: FlowedMoveData,
    allLoans: List<Loan>,
    body: RsBlock
) {
    val owner = body.descendantsOfType<RsFunction>().firstOrNull() ?: return
    val clcx = CheckLoanContext(bccx, dfcxLoans, moveData, allLoans)
}

sealed class UseError {
    object OK : UseError()
    class WhileBorrowed(loanPath: LoanPath) : UseError()
}

class CheckLoanContext(
    val bccx: BorrowCheckContext,
    val dfcxLoans: LoanDataFlow,
    val moveData: FlowedMoveData,
    val allLoans: List<Loan>
    // val paramEnv: ParamEnv,
    // val movableGenerator: Boolean
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        consumeCommon(element, cmt, mode)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {}

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        consumeCommon(pat, cmt, mode)
    }

    override fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, kind: BorrowKind, cause: LoanCause) {
        val loanPath = loanPathIsField(cmt).first
        if (loanPath != null) {
            val movedValueUseKind = if (cause == LoanCause.ClosureCapture) MovedInCapture else MovedInUse
            checkIfPathIsMoved(element, movedValueUseKind, loanPath)
        }
        checkForConflictingLoans(element)
        // checkForLoansAcrossYields(cmt, loanRegion)
    }

    private fun checkForConflictingLoans(element: RsElement) {
        val newLoanIndices = loansGeneratedBy(element)

        for (newLoanIndex in newLoanIndices) {
            eachIssuedLoan(element) { issuedLoan ->
                val newLoan = allLoans[newLoanIndex]
                reportErrorIfLoansConflict(issuedLoan, newLoan)
            }
        }

        for ((i, newLoanIndex) in newLoanIndices.withIndex()) {
            val oldLoan = allLoans[newLoanIndex]
            for (index in newLoanIndices.drop(i)) {
                val newLoan = allLoans[index]
                reportErrorIfLoansConflict(oldLoan, newLoan)
            }
        }

    }

    /** Checks whether [oldLoan] and [newLoan] can safely be issued simultaneously. */
    private fun reportErrorIfLoansConflict(oldLoan: Loan, newLoan: Loan): Boolean {
        // Should only be called for loans that are in scope at the same time.
        testAssert { bccx.regionScopeTree.areScopesIntersect(oldLoan.killScope, newLoan.killScope) }

        val errorOldNew = reportErrorIfLoanConflictsWithRestriction(oldLoan, newLoan, oldLoan, newLoan)
        val errorNewOld = reportErrorIfLoanConflictsWithRestriction(newLoan, oldLoan, oldLoan, newLoan)

        if (errorOldNew != null && errorNewOld != null) {
            errorOldNew.error.emit()
            errorNewOld.error.cancel()
        } else if (errorOldNew != null) {
            errorOldNew.error.emit()
        } else if (errorNewOld != null) {
            errorNewOld.error.emit()
        } else {
            return true
        }

        return false
    }

    private fun eachIssuedLoan(element: RsElement, op: (Loan) -> Boolean): Boolean =
        dfcxLoans.eachBitOnEntry(element) { op(allLoans[it]) }

    private fun loansGeneratedBy(element: RsElement): List<Int> {
        val result = mutableListOf<Int>()
        dfcxLoans.eachGenBit(element) { result.add(it) }
        return result
    }

    private fun checkIfPathIsMoved(element: RsElement, useKind: MovedValueUseKind, loanPath: LoanPath) {
        val baseLoanPath = loanPath
        moveData.eachMoveOf(element, baseLoanPath) { move, movedLp ->
            bccx.reportUseOfMovedValue(useKind, loanPath, move, movedLp)
            false
        }
    }

    override fun declarationWithoutInit(element: RsElement) {}

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        val loanPath = loanPathIsField(assigneeCmt).first
        if (loanPath != null) {
            when (mode) {
                MutateMode.Init, MutateMode.JustWrite -> {
                    // In a case like `path = 1`, path does not have to be *FULLY* initialized, but we still
                    // must be careful lest it contains derefs of pointers.
                    checkIfAssignedPathIsMoved(assigneeCmt.element, MovedInUse, loanPath)
                }
                MutateMode.WriteAndRead -> {
                    // In a case like `path += 1`, path must be fully initialized, since we will read it before we write it
                    checkIfPathIsMoved(assigneeCmt.element, MovedInUse, loanPath)
                }
            }
        }
        checkAssignment(assignmentElement, assigneeCmt)
    }

    private fun checkAssignment(assignmentElement: RsElement, assigneeCmt: Cmt) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun consumeCommon(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        val loanPath = loanPathIsField(cmt).first ?: return
        val movedValueUseKind = when (mode) {
            is ConsumeMode.Copy -> {
                checkForCopyOrFrozenPath(element, loanPath)
                MovedInUse
            }
            is ConsumeMode.Move -> {
                val moveKind = moveData.kindOfMoveOfPath(element, loanPath) ?: MovedInUse
                checkForMoveOfBorrowedPath(element, loanPath, moveKind)
                if (moveKind == MoveKind.Captured) MovedInCapture else MovedInUse
            }
        }
        checkIfPathIsMoved(element, movedValueUseKind, loanPath)
    }

    private fun checkForCopyOrFrozenPath(element: RsElement, copyPath: LoanPath) {
        val error = analyzeRestrictionsOnUse(element, copyPath, BorrowKind.ImmutableBorrow)
        if (error is UseError.WhileBorrowed) {

        }
    }

    private fun analyzeRestrictionsOnUse(element: RsElement, usePath: LoanPath, borrowKind: BorrowKind): UseError {
        var result: UseError = UseError.OK
        eachInScopeLoanAffectingPath(Scope.createNode(element), usePath) { loan ->
            if (!BorrowKind.isCompatible(loan.kind, borrowKind)) {
                result = UseError.WhileBorrowed(loan.loanPath)
                false
            } else {
                true
            }
        }

        return result
    }
}
