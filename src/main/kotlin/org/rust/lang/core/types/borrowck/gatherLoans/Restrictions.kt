/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.namedFields
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.AliasableViolationKind.BorrowViolation
import org.rust.lang.core.types.borrowck.BorrowCheckErrorCode.BorrowedPointerTooShort
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.borrowck.gatherLoans.RestrictionResult.Safe
import org.rust.lang.core.types.borrowck.gatherLoans.RestrictionResult.SafeIf
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.InteriorKind.InteriorField
import org.rust.lang.core.types.infer.PointerKind
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown

sealed class RestrictionResult {
    object Safe : RestrictionResult()
    class SafeIf(val loanPath: LoanPath, val loanPaths: MutableList<LoanPath>) : RestrictionResult()
}

class RestrictionContext(val bccx: BorrowCheckContext, val loanRegion: Region, val cause: LoanCause) {
    // TODO: refactor
    fun restrict(cmt: Cmt): RestrictionResult {
        fun loanPath(kind: LoanPathKind): LoanPath = LoanPath(kind, cmt.ty)

        val category = cmt.category
        return when (category) {
            is Categorization.Rvalue -> Safe

            is Categorization.StaticItem -> Safe

            is Categorization.Upvar -> SafeIf(loanPath(Upvar()), mutableListOf(loanPath(Upvar())))

            is Categorization.Local -> {
                val original = category.element
                val local = original.localElement
                val variable = Var(local, original)
                SafeIf(loanPath(variable), mutableListOf(loanPath(variable)))
            }

            is Categorization.Deref -> {
                val baseCmt = category.cmt
                val pointerKind = category.pointerKind
                when (pointerKind) {
                    is PointerKind.BorrowedPointer -> {
                        if (!bccx.isSubRegionOf(loanRegion, pointerKind.region)) {
                            val errorCode = BorrowedPointerTooShort(loanRegion, pointerKind.region)
                            bccx.report(BorrowCheckError(BorrowViolation(cause), baseCmt, errorCode))
                            return Safe
                        }

                        when (pointerKind.borrowKind) {
                            BorrowKind.ImmutableBorrow -> Safe
                            BorrowKind.MutableBorrow -> extend(restrict(baseCmt), cmt, LoanPathElement.Deref(pointerKind))
                        }
                    }
                    is PointerKind.UnsafePointer -> Safe
                }
            }

            is Categorization.Interior -> {
                val interiorKind = category.interiorKind
                val baseCmt = category.cmt
                val baseType = baseCmt.ty
                val variant = (baseCmt.category as? Categorization.Downcast)?.element
                val result = restrict(baseCmt)

                fun processFields(item: RsStructItem, result: SafeIf) {
                    item.namedFields.forEachIndexed { i, field ->
                        val fieldInteriorKind = InteriorField(field.name)
                        val fieldType = if (fieldInteriorKind == interiorKind) cmt.ty else TyUnknown
                        val siblingLpKind = Extend(result.loanPath, cmt.mutabilityCategory, Interior(variant, fieldInteriorKind))
                        val siblingLp = LoanPath(siblingLpKind, fieldType)
                        result.loanPaths.add(siblingLp)
                    }
                }

                // Borrowing one union field automatically borrows all its fields.
                return if (baseType is TyAdt && baseType.item is RsStructItem && baseType.item.isUnion) {
                    when (result) {
                        is Safe -> Safe
                        is SafeIf -> {
                            processFields(baseType.item, result)
                            val lp = loanPath(Extend(result.loanPath, cmt.mutabilityCategory, Interior(variant, interiorKind)))
                            SafeIf(lp, result.loanPaths)
                        }
                    }
                } else {
                    extend(result, cmt, Interior(variant, interiorKind))
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
