/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.infer.Aliasability.FreelyAliasable
import org.rust.lang.core.types.infer.Aliasability.NonAliasable
import org.rust.lang.core.types.infer.AliasableReason.AliasableStatic
import org.rust.lang.core.types.infer.AliasableReason.AliasableStaticMut
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.BorrowKind.MutableBorrow
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope

fun gatherLoansInFn(bccx: BorrowCheckContext, body: RsBlock): Pair<List<Loan>, MoveData> {
    val glcx = GatherLoanContext(bccx, MoveData(), emptyList(), Scope.createNode(body))
    val visitor = ExprUseVisitor(glcx, MemoryCategorizationContext(bccx.regionScopeTree))
    visitor.consumeBody(bccx.body)
    // glcx.reportPotentialErrors()
    return Pair(glcx.allLoans, glcx.moveData)
}

class GatherLoanContext(
    val bccx: BorrowCheckContext,
    val moveData: MoveData,
    // val moveErrorCollector: MoveErrorCollector,
    val allLoans: List<Loan>,
    val itemUpperBound: Scope
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gatherMoveFromExpr(bccx, moveData, element, cmt, mode.reason)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {
    }

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gatherMoveFromPat(bccx, moveData, pat, cmt)
    }

    override fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, kind: BorrowKind, cause: LoanCause) {
        guaranteeValid(element, cmt, kind, loanRegion, cause)
    }

    override fun declarationWithoutInit(element: RsElement) {
        gatherDeclaration(bccx, moveData, element)
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        guaranteeAssignmentValid(assignmentElement, assigneeCmt, mode)
    }

    fun guaranteeAssignmentValid(assignment: RsElement, cmt: Cmt, mode: MutateMode) {

    }
}

fun checkAliasability(bccx: BorrowCheckContext, cause: LoanCause, cmt: Cmt, requiredKind: BorrowKind): Boolean {
    val aliasability = cmt.aliasability

    // Uniquely accessible path -- OK for `&` and `&mut`
    if (aliasability is NonAliasable) {
        return true
    }

    // Borrow of an immutable static item.
    if (aliasability is FreelyAliasable && aliasability.reason == AliasableStatic && requiredKind is ImmutableBorrow) {
        return true
    }

    // Even touching a static mut is considered unsafe. We assume the user knows what they're doing in these cases.
    if (aliasability is FreelyAliasable && aliasability.reason == AliasableStaticMut) {
        return true
    }

    if (aliasability is FreelyAliasable && requiredKind is MutableBorrow) {
        bccx.reportAliasabilityViolation(cause, aliasability.reason, cmt)
        return false
    }

    return true
}

fun checkMutability(
    bccx: BorrowCheckContext,
    cause: AliasableViolationKind,
    cmt: Cmt,
    requiredKind: BorrowKind
): Boolean =
    if (requiredKind is ImmutableBorrow || cmt.isMutable) {
        true
    } else {
        bccx.report()
        false
    }


sealed class AliasableViolationKind {
    object MutabilityViolation : AliasableViolationKind()
    class BorrowViolation(val cause: LoanCause) : AliasableViolationKind()
}
