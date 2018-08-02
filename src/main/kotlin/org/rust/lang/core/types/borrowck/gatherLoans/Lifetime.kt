/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.BorrowCheckContext
import org.rust.lang.core.types.borrowck.LoanCause
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.Cmt
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

    fun scope(cmt: Cmt): Region {

    }
}
