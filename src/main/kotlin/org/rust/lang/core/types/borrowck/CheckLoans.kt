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

fun checkLoans(
    bccx: BorrowCheckContext,
    dataFlowLoans: LoanDataFlow,
    moveData: FlowedMoveData,
    allLoans: List<Loan>,
    body: RsBlock
) {
    val owner = body.descendantsOfType<RsFunction>().firstOrNull() ?: return
    val clcx = CheckLoanContext()

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
        checkForLoansAcrossYields(cmt, loanRegion)
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
        val loanPath = loanPathIsField(cmt).first
        if (loanPath != null) {
            val movedValueUseKind =
                when (mode) {
                    is ConsumeMode.Copy -> {
                        checkForCopyOrFrozenPath(element, loanPath)
                        MovedInUse
                    }
                    is ConsumeMode.Move -> {

                    }
                }
        }
    }
}
