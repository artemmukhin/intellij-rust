/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.borrowck.MovedValueUseKind.MovedInCapture
import org.rust.lang.core.types.borrowck.MovedValueUseKind.MovedInUse
import org.rust.lang.core.types.borrowck.gatherLoans.hasDestructor
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.ty.TyAdt

fun checkLoans(
    bccx: BorrowCheckContext,
    dfcxLoans: LoanDataFlow,
    moveData: FlowedMoveData,
    allLoans: List<Loan>,
    body: RsBlock
) {
    val clcx = CheckLoanContext(bccx, dfcxLoans, moveData, allLoans)
    val visitor = ExprUseWalker(clcx, MemoryCategorizationContext(bccx.implLookup.ctx))
    visitor.consumeBody(body)
}

sealed class UseError {
    object OK : UseError()
    class WhileBorrowed(val loanPath: LoanPath) : UseError()
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
        val loanPath = LoanPath.computeFor(cmt)
        if (loanPath != null) {
            val movedValueUseKind = if (cause == LoanCause.ClosureCapture) MovedInCapture else MovedInUse
            checkIfPathIsMoved(element, movedValueUseKind, loanPath)
        }
        checkForConflictingLoans(element)
        // checkForLoansAcrossYields(cmt, loanRegion)
    }

    /**
     * Checks to see whether any of the loans that are issued on entrance to [element] conflict with loans
     * that have already been issued when we enter [element].
     * For example, we don't permit two `&mut` borrows of the same variable.
     */
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

    /**
     * Iterates over each loan that has been issued on entrance to [element], regardless of whether it is
     * actually in scope at that point.  Sometimes loans are issued for future scopes and thus they may have been
     * issued but not yet be in effect.
     */
    fun eachIssuedLoan(element: RsElement, op: (Loan) -> Boolean): Boolean =
        dfcxLoans.eachBitOnEntry(element) { op(allLoans[it]) }

    /** Like [eachIssuedLoan], but only considers loans that are currently in scope. */
    fun eachInScopeLoan(scope: Scope, op: (Loan) -> Boolean): Boolean =
        eachIssuedLoan(scope.element) { loan ->
            if (bccx.regionScopeTree.isSubScopeOf(scope, loan.killScope)) {
                op(loan)
            } else {
                true
            }
        }

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
        val loanPath = LoanPath.computeFor(assigneeCmt)
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

    /**
     * Reports an error if assigning to [loanPath] will use a moved/uninitialized value.
     * Mainly this is concerned with detecting derefs of uninitialized pointers.
     */
    private fun checkIfAssignedPathIsMoved(element: RsElement, useKind: MovedValueUseKind, loanPath: LoanPath) {
        val kind = loanPath.kind
        if (kind is Downcast) {
            // assigning to `(P->Variant).f` is ok if assigning to `P` is ok
            checkIfAssignedPathIsMoved(element, useKind, loanPath)
        } else if (kind is Extend) {
            val baseLoanPath = kind.loanPath
            val lpElement = kind.lpElement
            if (lpElement is LoanPathElement.Interior) {
                val lpElementKind = lpElement.kind
                if (lpElementKind is InteriorKind.InteriorField) {
                    if (baseLoanPath.ty is TyAdt && baseLoanPath.ty.item.hasDestructor) {
                        moveData.eachMoveOf(element, baseLoanPath) { _, _ ->
                            // TODO: bccx.report()
                            false
                        }
                        return
                    }
                    checkIfAssignedPathIsMoved(element, useKind, baseLoanPath)
                } else if (lpElementKind is InteriorKind.InteriorIndex || lpElementKind is InteriorKind.InteriorPattern) {
                    checkIfPathIsMoved(element, useKind, baseLoanPath)
                }
            } else if (lpElement is LoanPathElement.Deref) {
                checkIfPathIsMoved(element, useKind, baseLoanPath)
            }
        }
    }

    private fun checkAssignment(assignmentElement: RsElement, assigneeCmt: Cmt) {
        // Check that we don't invalidate any outstanding loans
        LoanPath.computeFor(assigneeCmt)?.let { loanPath ->
            val scope = Scope.Node(assignmentElement)
            eachInScopeLoanAffectingPath(scope, loanPath) { loan ->
                reportIllegalMutation(loanPath, loan)
                false
            }
        }

        // Check for reassignments to local variables. This needs to be done here because we depend on move data.
        val cat = assigneeCmt.category
        if (cat is Categorization.Local) {
            val loanPath = LoanPath.computeFor(assigneeCmt) ?: return
            moveData.eachAssignmentOf(assignmentElement, loanPath) { assign ->
                if (assigneeCmt.isMutable) {
                    bccx.usedMutNodes.add(cat.element)
                } else {
                    bccx.reportReassignedImmutableVariable(loanPath, assign)
                }
                false
            }
        }
    }

    private fun eachInScopeLoanAffectingPath(scope: Scope, loanPath: LoanPath, op: (Loan) -> Boolean): Boolean {
        // First, we check for a loan restricting the path P being used. This accounts for borrows of P
        // but also borrows of subpaths, like P.a.b. Consider the following example:
        //     let x = &mut a.b.c; // Restricts a, a.b, and a.b.c
        //     let y = a;          // Conflicts with restriction

        val cont = eachInScopeLoan(scope) { loan: Loan -> loan.restrictedPaths.all { it != loanPath || op(loan) } }

        if (!cont) return false

        // Next, we must check for *loans* (not restrictions) on the path P or any base path.
        // This rejects examples like the following:
        //     let x = &mut a.b;
        //     let y = a.b.c;
        //
        // Limiting this search to loans and not restrictions means that examples like the following continue to work:
        //     let x = &mut a.b;
        //     let y = a.c;

        var lp = loanPath
        while (true) {
            val kind = lp.kind
            if (kind is Var || kind is Upvar) break

            if (kind is Downcast) lp = kind.loanPath
            if (kind is Extend) lp = kind.loanPath

            val cont = eachInScopeLoan(scope) { loan: Loan ->
                if (loan.loanPath == lp) op(loan) else true
            }

            if (!cont) return false
        }

        return true
    }

    fun consumeCommon(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        val loanPath = LoanPath.computeFor(cmt) ?: return
        val movedValueUseKind = when (mode) {
            is ConsumeMode.Copy -> {
                checkForCopyOrFrozenPath(element, loanPath)
                MovedInUse
            }
            is ConsumeMode.Move -> {
                val moveKind = moveData.moveKindOfPath(element, loanPath)
                if (moveKind == null) {
                    // sometimes moves don't have a move kind; this either means that the original move was from
                    // something illegal to move, or was moved from referent of an unsafe pointer
                    MovedInUse
                } else {
                    checkForMoveOfBorrowedPath(element, loanPath, moveKind)
                    if (moveKind == MoveKind.Captured) MovedInCapture else MovedInUse
                }
            }
        }
        checkIfPathIsMoved(element, movedValueUseKind, loanPath)
    }

    private fun checkForMoveOfBorrowedPath(element: RsElement, movePath: LoanPath, moveKind: MoveKind) {
        val error = analyzeRestrictionsOnUse(element, movePath, BorrowKind.MutableBorrow)
        if (error is UseError.WhileBorrowed) {
            // Some error for "cannot move when borrowed"
        }
    }

    private fun checkForCopyOrFrozenPath(element: RsElement, copyPath: LoanPath) {
        val error = analyzeRestrictionsOnUse(element, copyPath, BorrowKind.ImmutableBorrow)
        if (error is UseError.WhileBorrowed) {
            // Some error for "cannot use when mutably borrowed"
        }
    }

    private fun analyzeRestrictionsOnUse(element: RsElement, usePath: LoanPath, borrowKind: BorrowKind): UseError {
        var result: UseError = UseError.OK
        eachInScopeLoanAffectingPath(Scope.Node(element), usePath) { loan ->
            if (!BorrowKind.isCompatible(loan.kind, borrowKind)) {
                result = UseError.WhileBorrowed(loan.loanPath)
                false
            } else {
                true
            }
        }

        return result
    }

    /** Checks whether [oldLoan] and [newLoan] can safely be issued simultaneously. */
    private fun reportErrorIfLoansConflict(oldLoan: Loan, newLoan: Loan): Boolean {
        // Should only be called for loans that are in scope at the same time.
        if (!bccx.regionScopeTree.intersects(oldLoan.killScope, newLoan.killScope)) return false

        val errorOldNew = reportErrorIfLoanConflictsWithRestriction(oldLoan, newLoan, oldLoan, newLoan)
        val errorNewOld = reportErrorIfLoanConflictsWithRestriction(newLoan, oldLoan, oldLoan, newLoan)

        // TODO
        if (errorOldNew != null && errorNewOld != null) {
            // errorOldNew.error.emit()
            // errorNewOld.error.cancel()
        } else if (errorOldNew != null) {
            // errorOldNew.error.emit()
        } else if (errorNewOld != null) {
            // errorNewOld.error.emit()
        } else {
            return true
        }

        return false
    }

    // TODO
    /** Checks whether the restrictions introduced by `loan1` would prohibit `loan2` */
    private fun reportErrorIfLoanConflictsWithRestriction(loan1: Loan, loan2: Loan, oldLoan: Loan, newLoan: Loan): Any? {
        if (BorrowKind.isCompatible(loan1.kind, loan2.kind)) {
            return true
        }

        for (restrictedPath in loan1.restrictedPaths) {
            if (restrictedPath != loan2.loanPath) continue

            val common = newLoan.loanPath.common(oldLoan.loanPath)
        }
        return false
    }

    private fun reportIllegalMutation(loanPath: LoanPath, loan: Loan) {
        // TODO
    }
}
