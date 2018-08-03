/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsStructKind
import org.rust.lang.core.psi.ext.kind
import org.rust.lang.core.psi.ext.namedFields
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.borrowck.gatherLoans.AliasableViolationKind.BorrowViolation
import org.rust.lang.core.types.borrowck.gatherLoans.RestrictionResult.Safe
import org.rust.lang.core.types.borrowck.gatherLoans.RestrictionResult.SafeIf
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown

sealed class RestrictionResult {
    object Safe : RestrictionResult()
    class SafeIf(val loanPath: LoanPath, val loanPaths: MutableList<LoanPath>) : RestrictionResult()
}

fun computeRestrictions(bccx: BorrowCheckContext, cause: LoanCause, cmt: Cmt, loanRegion: Region): RestrictionResult {
    val context = RestrictionContext(bccx, loanRegion, cause)
    return context.restrict(cmt)
}

class RestrictionContext(val bccx: BorrowCheckContext, val loanRegion: Region, val cause: LoanCause) {
    fun restrict(cmt: Cmt): RestrictionResult {
        fun loanPath(kind: LoanPathKind): LoanPath = LoanPath(kind, cmt.ty)

        val category = cmt.category
        return when (category) {
            is Categorization.Rvalue -> Safe
            is Categorization.StaticItem -> Safe
            is Categorization.Upvar -> SafeIf(loanPath(Upvar()), mutableListOf(loanPath(Upvar())))
            is Categorization.Local -> SafeIf(loanPath(Var(category.element)), mutableListOf(loanPath(Var(category.element))))
            is Categorization.Deref -> {
                val pointerKind = category.pointerKind
                when (pointerKind) {
                    is PointerKind.BorrowedPointer -> {
                        if (bccx.isSubregionOf(loanRegion, pointerKind.region)) {
                            bccx.report(BorrowCheckError(BorrowViolation(cause), category.cmt, BorrowCheckErrorCode.BorrowedPointerTooShort(loanRegion, pointerKind.region)))
                            return Safe
                        }

                        when (pointerKind.borrowKind) {
                            BorrowKind.ImmutableBorrow -> Safe
                            BorrowKind.MutableBorrow -> extend(restrict(category.cmt), cmt, LoanPathElement.Deref(pointerKind))
                        }
                    }
                    is PointerKind.UnsafePointer -> TODO()
                }
            }
            is Categorization.Interior -> {
                val baseCmt = category.cmt
                val baseType = baseCmt.ty
                val variant = (baseCmt.category as? Categorization.Downcast)?.element
                val result = restrict(baseCmt)
                if (baseType is TyAdt && (baseType.item as? RsStructItem)?.kind == RsStructKind.UNION) {
                    return when (result) {
                        is Safe -> Safe
                        is SafeIf -> {
                            baseType.item.namedFields.forEachIndexed { i, field ->
                                val fieldInteriorKind = InteriorKind.InteriorField(FieldIndex(i, field.name))
                                val fieldType = if (fieldInteriorKind == category.interiorKind) cmt.ty else TyUnknown
                                val siblingLpKind = Extend(result.loanPath, cmt.mutabilityCategory, LoanPathElement.Interior(variant, fieldInteriorKind))
                                val siblingLp = LoanPath(siblingLpKind, fieldType)
                                result.loanPaths.add(siblingLp)
                            }
                            val lp = loanPath(Extend(result.loanPath, cmt.mutabilityCategory, LoanPathElement.Interior(variant, category.interiorKind)))
                            SafeIf(lp, result.loanPaths)
                        }
                    }
                } else {
                    return extend(result, cmt, LoanPathElement.Interior(variant, category.interiorKind))
                }
            }

            is Categorization.Downcast -> restrict(category.cmt)
            null -> Safe
        }
    }

    fun extend(result: RestrictionResult, cmt: Cmt, lpElement: LoanPathElement): RestrictionResult =
        when (result) {
            is Safe -> Safe
            is SafeIf -> {
                val v = Extend(result.loanPath, cmt.mutabilityCategory, lpElement)
                val lp = LoanPath(v, cmt.ty)
                result.loanPaths.add(lp)
                SafeIf(lp, result.loanPaths)
            }
        }
}
