/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsExprStmt
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.RsPatBinding
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type

fun gatherLoansInFn(bccx: BorrowCheckContext): MoveData {
    val glcx = GatherLoanContext(bccx, MoveData())
    val visitor = ExprUseWalker(glcx, MemoryCategorizationContext(bccx.implLookup.ctx))
    visitor.consumeBody(bccx.body)
    return glcx.moveData
}

class GatherLoanContext(bccx: BorrowCheckContext, val moveData: MoveData) : Delegate {
    private val gmcx = GatherMoveContext(bccx)

    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gmcx.gatherMoveFromExpr(moveData, element, cmt, mode.reason)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {}

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gmcx.gatherMoveFromPat(moveData, pat, cmt)
    }

    override fun declarationWithoutInit(element: RsElement) {
        val type = when (element) {
            is RsExpr -> element.type
            is RsExprStmt -> element.expr.type
            is RsPatBinding -> element.type
            else -> TyUnknown
        }
        gmcx.gatherDeclaration(moveData, element, type)
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        guaranteeAssignmentValid(assignmentElement, assigneeCmt, mode)
    }

    /** Guarantees that [cmt] is assignable, or reports an error. */
    private fun guaranteeAssignmentValid(assignment: RsElement, cmt: Cmt, mode: MutateMode) {
        // `loanPath` may be null with e.g. `*foo() = 5`.
        // In such cases, there is no need to check for conflicts with moves etc, just ignore.
        val loanPath = LoanPath.computeFor(cmt) ?: return

        val assignmentData = AssignmentData(assignment, loanPath, cmt.element)
        gmcx.gatherAssignment(moveData, assignmentData, mode)
    }
}

class AssignmentData(
    val assignment: RsElement,
    val loanPath: LoanPath,
    val assignee: RsElement
)
