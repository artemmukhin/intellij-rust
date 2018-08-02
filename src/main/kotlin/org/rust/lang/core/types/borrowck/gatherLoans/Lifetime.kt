/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.BorrowCheckContext
import org.rust.lang.core.types.borrowck.BorrowCheckError
import org.rust.lang.core.types.borrowck.BorrowCheckErrorCode
import org.rust.lang.core.types.borrowck.LoanCause
import org.rust.lang.core.types.borrowck.gatherLoans.AliasableViolationKind.BorrowViolation
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.PointerKind.BorrowedPointer
import org.rust.lang.core.types.infer.PointerKind.UnsafePointer
import org.rust.lang.core.types.regions.ReScope
import org.rust.lang.core.types.regions.ReStatic
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope

fun guaranteeLifetime(
    bccx: BorrowCheckContext,
    itemScope: Scope,
    cause: LoanCause,
    cmt: Cmt,
    loanRegion: Region
): Boolean {
    val context = GuaranteeLifetimeContext(bccx, itemScope, cause, cmt, loanRegion)
    return context.check(cmt, null)
}

class GuaranteeLifetimeContext(
    val bccx: BorrowCheckContext,
    val itemScope: Scope,
    val cause: LoanCause,
    val cmt: Cmt,
    val loanRegion: Region
) {
    fun check(cmt: Cmt, disriminantScope: RsElement?): Boolean =
        when (cmt.category) {
            is Rvalue, is Local, is Upvar, is Deref -> checkScope(scope(cmt))
            is StaticItem -> true
            is Interior -> check(cmt.category.cmt, disriminantScope)
            is Downcast -> check(cmt.category.cmt, disriminantScope)
            null -> true
        }

    fun checkScope(maxScope: Region): Boolean =
        !bccx.isSubregionOf(loanRegion, maxScope)

    /**
     * Returns the maximal region scope for the which the place [cmt] is guaranteed
     * to be valid without any rooting etc, and presuming [cmt] is not mutated.
     */
    fun scope(cmt: Cmt): Region {
        val category = cmt.category
        return when (category) {
            is Rvalue -> category.region
            is StaticItem -> ReStatic
            is Upvar -> ReScope(itemScope)
            is Local -> ReScope(bccx.regionScopeTree.getVariableScope(category.element))
            is Deref -> {
                val pointerKind = category.pointerKind
                when (pointerKind) {
                    is UnsafePointer -> ReStatic
                    is BorrowedPointer -> pointerKind.region
                }
            }
            is Interior -> scope(category.cmt)
            is Downcast -> scope(category.cmt)
            null -> ReStatic
        }
    }

    fun reportError(code: BorrowCheckErrorCode) {
        bccx.report(BorrowCheckError(BorrowViolation(cause), cmt, code))
    }
}
