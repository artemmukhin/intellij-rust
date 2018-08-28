/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.LoanPathKind.Downcast
import org.rust.lang.core.types.borrowck.LoanPathKind.Extend
import org.rust.lang.core.types.borrowck.gatherLoans.hasDestructor
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.ty.TyAdt

fun checkLoans(
    bccx: BorrowCheckContext,
    moveData: FlowedMoveData,
    body: RsBlock
) {
    val clcx = CheckLoanContext(bccx, moveData)
    val visitor = ExprUseWalker(clcx, MemoryCategorizationContext(bccx.implLookup, bccx.owner.inference))
    visitor.consumeBody(body)
}

class CheckLoanContext(
    private val bccx: BorrowCheckContext,
    private val moveData: FlowedMoveData
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        consumeCommon(element, cmt)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {}

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        consumeCommon(pat, cmt)
    }

    private fun checkIfPathIsMoved(element: RsElement, loanPath: LoanPath) {
        moveData.eachMoveOf(element, loanPath) { move, _ ->
            bccx.reportUseOfMovedValue(loanPath, move)
            false
        }
    }

    override fun declarationWithoutInit(element: RsElement) {}

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        val loanPath = LoanPath.computeFor(assigneeCmt)
        if (loanPath != null) {
            when (mode) {
                MutateMode.Init, MutateMode.JustWrite -> {
                    // In a case like `path = 1`, path does not have to be FULLY initialized, but we still
                    // must be careful lest it contains derefs of pointers.
                    checkIfAssignedPathIsMoved(assigneeCmt.element, loanPath)
                }
                MutateMode.WriteAndRead -> {
                    // In a case like `path += 1`, path must be fully initialized, since we will read it before we write it
                    checkIfPathIsMoved(assigneeCmt.element, loanPath)
                }
            }
        }
    }

    /**
     * Reports an error if assigning to [loanPath] will use a moved/uninitialized value.
     * Mainly this is concerned with detecting derefs of uninitialized pointers.
     */
    private fun checkIfAssignedPathIsMoved(element: RsElement, loanPath: LoanPath) {
        val kind = loanPath.kind
        if (kind is Downcast) {
            // assigning to `(P->Variant).f` is ok if assigning to `P` is ok
            checkIfAssignedPathIsMoved(element, loanPath)
        } else if (kind is Extend) {
            val baseLoanPath = kind.loanPath
            val lpElement = kind.lpElement
            if (lpElement is LoanPathElement.Interior) {
                val lpElementKind = lpElement.kind
                if (lpElementKind is InteriorKind.InteriorField) {
                    if (baseLoanPath.ty is TyAdt && baseLoanPath.ty.item.hasDestructor) {
                        moveData.eachMoveOf(element, baseLoanPath) { _, _ -> false }
                        return
                    }
                    checkIfAssignedPathIsMoved(element, baseLoanPath)
                } else if (lpElementKind is InteriorKind.InteriorIndex || lpElementKind is InteriorKind.InteriorPattern) {
                    checkIfPathIsMoved(element, baseLoanPath)
                }
            } else if (lpElement is LoanPathElement.Deref) {
                checkIfPathIsMoved(element, baseLoanPath)
            }
        }
    }

    private fun consumeCommon(element: RsElement, cmt: Cmt) {
        val loanPath = LoanPath.computeFor(cmt) ?: return
        checkIfPathIsMoved(element, loanPath)
    }
}
