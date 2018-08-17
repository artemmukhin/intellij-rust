/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsPatBinding
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.AliasableViolationKind.BorrowViolation
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.PointerKind.BorrowedPointer
import org.rust.lang.core.types.infer.PointerKind.UnsafePointer
import org.rust.lang.core.types.regions.ReScope
import org.rust.lang.core.types.regions.ReStatic
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope

class GuaranteeLifetimeContext(
    val bccx: BorrowCheckContext,
    val itemScope: Scope,
    val cause: LoanCause,
    val cmt: Cmt,
    val loanRegion: Region
) {
    fun check(cmt: Cmt, disriminantScope: RsElement?): Boolean =
        when (cmt.category) {
            is Rvalue, is Local, is Upvar, is Deref -> checkScope(maximalScope(cmt))
            is StaticItem -> true
            is Interior -> check(cmt.category.cmt, disriminantScope)
            is Downcast -> check(cmt.category.cmt, disriminantScope)
            null -> true
        }

    private fun checkScope(maxScope: Region): Boolean {
        if (!bccx.isSubRegionOf(loanRegion, maxScope)) {
            reportError(BorrowCheckErrorCode.OutOfScope(maxScope, loanRegion, cause))
            return false
        }
        return true
    }

    /**
     * Returns the maximal region scope for the which the place [cmt] is guaranteed
     * to be valid without any rooting etc, and presuming [cmt] is not mutated.
     */
    private fun maximalScope(cmt: Cmt): Region {
        val category = cmt.category
        return when (category) {
            is Rvalue -> category.region
            is StaticItem -> ReStatic
            is Upvar -> ReScope(itemScope)
            is Local -> {
                val variable = category.element.localElement
                if (variable is RsPatBinding) {
                    ReScope(bccx.regionScopeTree.getVariableScope(variable) ?: Scope.Node(variable))
                } else {
                    ReScope(Scope.Node(variable))
                }
            }
            is Deref -> {
                val pointerKind = category.pointerKind
                when (pointerKind) {
                    is UnsafePointer -> ReStatic
                    is BorrowedPointer -> pointerKind.region
                }
            }
            is Interior -> maximalScope(category.cmt)
            is Downcast -> maximalScope(category.cmt)
            null -> ReStatic
        }
    }

    private fun reportError(code: BorrowCheckErrorCode) {
        bccx.report(BorrowCheckError(BorrowViolation(cause), cmt, code))
    }
}
