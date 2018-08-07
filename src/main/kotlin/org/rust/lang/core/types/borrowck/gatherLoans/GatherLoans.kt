/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.gatherLoans.AliasableViolationKind.BorrowViolation
import org.rust.lang.core.types.borrowck.gatherLoans.AliasableViolationKind.MutabilityViolation
import org.rust.lang.core.types.borrowck.gatherLoans.RestrictionResult.SafeIf
import org.rust.lang.core.types.infer.Aliasability.FreelyAliasable
import org.rust.lang.core.types.infer.Aliasability.NonAliasable
import org.rust.lang.core.types.infer.AliasableReason.AliasableStatic
import org.rust.lang.core.types.infer.AliasableReason.AliasableStaticMut
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.BorrowKind.MutableBorrow
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.regions.*

fun gatherLoansInFn(bccx: BorrowCheckContext, body: RsBlock): Pair<List<Loan>, MoveData> {
    val glcx = GatherLoanContext(bccx, MoveData(), null, mutableListOf(), Scope.createNode(body))
    val visitor = ExprUseVisitor(glcx, MemoryCategorizationContext(bccx.regionScopeTree))
    visitor.consumeBody(bccx.body)
    // glcx.reportPotentialErrors()
    return Pair(glcx.allLoans, glcx.moveData)
}

class GatherLoanContext(
    val bccx: BorrowCheckContext,
    val moveData: MoveData,
    val moveErrorCollector: MoveErrorCollector,
    val allLoans: MutableList<Loan>,
    val itemUpperBound: Scope
) : Delegate {
    override fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gatherMoveFromExpr(bccx, moveData, moveErrorCollector, element, cmt, mode.reason)
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {
    }

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
        if (mode is ConsumeMode.Move) gatherMoveFromPat(bccx, moveData, moveErrorCollector, pat, cmt)
    }

    override fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, kind: BorrowKind, cause: LoanCause) {
        guaranteeValid(element, cmt, kind, loanRegion, cause)
    }

    override fun declarationWithoutInit(element: RsElement) {
        gatherDeclaration(bccx, moveData, element, element.type)
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
        guaranteeAssignmentValid(assignmentElement, assigneeCmt, mode)
    }

    /** Guarantees that [cmt] is assignable, or reports an error. */
    fun guaranteeAssignmentValid(assignment: RsElement, cmt: Cmt, mode: MutateMode) {
        val loanPath = loanPathIsField(cmt).first

        /** Only re-assignments to locals require it to be mutable - this is checked in [checkLoans] */
        if (cmt.category !is Categorization.Local && !checkMutability(bccx, MutabilityViolation, cmt, MutableBorrow)) {
            return
        }

        if (!checkAliasability(bccx, MutabilityViolation, cmt, MutableBorrow)) {
            return
        }

        // `loanPath` may be null with e.g. `*foo() = 5`.
        // In such cases, there is no need to check for conflicts with moves etc, just ignore.
        if (loanPath != null) {
            /** Only re-assignments to locals require it to be mutable - this is checked in [checkLoans] */
            if (cmt.category !is Categorization.Local) {
                markLoanPathAsMutated(loanPath)
            }
            gatherAssignment(bccx, moveData, assignment, loanPath, cmt.element, mode)
        }
    }

    /**
     * Guarantees that Address([cmt]) will be valid for the duration of `static_scope_r`, or reports an error.
     * This may entail taking out loans, which will be added to the [req_loan_map]
     */
    fun guaranteeValid(element: RsElement, cmt: Cmt, requiredKind: BorrowKind, loanRegion: Region, cause: LoanCause) {
        // A loan for the empty region can never be dereferenced, so it is always safe
        if (loanRegion is ReEmpty) return

        // Check that the lifetime of the borrow does not exceed the lifetime of the data being borrowed
        if (!guaranteeLifetime(bccx, itemUpperBound, cause, cmt, loanRegion)) return

        // Check that we don't allow mutable borrows of non-mutable data
        if (!checkMutability(bccx, BorrowViolation(cause), cmt, requiredKind)) return

        // Check that we don't allow mutable borrows of aliasable data
        if (!checkAliasability(bccx, BorrowViolation(cause), cmt, requiredKind)) return

        // Compute the restrictions that are required to enforce the loan is safe
        // No restrictions -- no loan record necessary
        val restriction = computeRestrictions(bccx, cause, cmt, loanRegion) as? SafeIf ?: return
        val loanPath = restriction.loanPath
        val restrictedPaths = restriction.loanPaths

        val loanScope = when (loanRegion) {
            is ReScope -> loanRegion.scope
            is ReEarlyBound -> bccx.regionScopeTree.getEarlyFreeScope(loanRegion)
            is ReFree -> bccx.regionScopeTree.getFreeScope(loanRegion)
            is ReStatic -> itemUpperBound
            else -> return // invalid borrow lifetime
        }

        val borrowScope = Scope.createNode(element)
        val genScope = computeGenScope(borrowScope, loanScope)
        val killScope = computeKillScope(loanScope, loanPath)

        if (requiredKind == MutableBorrow) markLoanPathAsMutated(loanPath)

        val loan = Loan(allLoans.size, loanPath, requiredKind, restrictedPaths, genScope, killScope, cause)
        allLoans.add(loan)
    }

    /**
     * For mutable loans of content whose mutability derives
     * from a local variable, mark the mutability decl as necessary.
     */
    fun markLoanPathAsMutated(loanPath: LoanPath) {
        var wrappedPath: LoanPath? = loanPath
        var throughBorrow = false

        while (wrappedPath != null) {
            val currentPath = wrappedPath
            val kind = currentPath.kind

            wrappedPath = when (kind) {
                is LoanPathKind.Var -> {
                    if (!throughBorrow) bccx.usedMutNodes.add(kind.element)
                    null
                }

                is LoanPathKind.Upvar -> {
                    // bccx.usedMutNodes.add(kind.element)
                    null
                }

                is LoanPathKind.Downcast -> kind.loanPath

                is LoanPathKind.Extend -> {
                    if (kind.mutCategory.isMutable) {
                        if (kind.lpElement is LoanPathElement.Deref) {
                            throughBorrow = true
                            kind.loanPath
                        } else {
                            kind.loanPath
                        }
                    } else {
                        null
                    }
                }
            }

        }
    }

    /**
     * Determine when to introduce the loan. Typically the loan is introduced at the point of the borrow
     * but in some cases, notably method arguments, the loan may be introduced only later, once it comes into scope.
     */
    fun computeGenScope(borrowScope: Scope, loanScope: Scope): Scope =
        if (bccx.regionScopeTree.isSubScopeOf(borrowScope, loanScope)) borrowScope else loanScope

    /**
     * Determine when the loan restrictions go out of scope. This is either when the lifetime expires or when the
     * local variable which roots the loan-path goes out of scope, whichever happens faster.
     *
     * It may seem surprising that we might have a loan region larger than the variable which roots the loan-path;
     * this can come about when variables of `&mut` type are re-borrowed, as in this example:
     *
     *      struct Foo { counter: u32 }
     *      fn counter<'a>(v: &'a mut Foo) -> &'a mut u32 {
     *          &mut v.counter
     *      }
     *
     * In this case, the reference (`'a`) outlives the variable `v` that hosts it.
     * Note that this doesn't come up with immutable `&` pointers, because borrows of such pointers
     * do not require restrictions and hence do not cause a loan.
     */
    fun computeKillScope(loanScope: Scope, loanPath: LoanPath): Scope {
        val lexicalScope = loanPath.killScope(bccx)
        return if (bccx.regionScopeTree.isSubScopeOf(lexicalScope, loanScope)) lexicalScope else loanScope
    }
}

fun checkAliasability(bccx: BorrowCheckContext, cause: AliasableViolationKind, cmt: Cmt, requiredKind: BorrowKind): Boolean {
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
        bccx.report(BorrowCheckError(cause, cmt, BorrowCheckErrorCode.Mutability))
        false
    }


sealed class AliasableViolationKind {
    object MutabilityViolation : AliasableViolationKind()
    class BorrowViolation(val cause: LoanCause) : AliasableViolationKind()
}
