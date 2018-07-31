/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.types.borrowck.BorrowCheckContext
import org.rust.lang.core.types.borrowck.Loan
import org.rust.lang.core.types.borrowck.MoveData
import org.rust.lang.core.types.regions.Scope

fun gatherLoansInFn(bccx: BorrowCheckContext, body: RsBlock): Pair<List<Loan>, MoveData> {
    val glcx = GatherLoanContext(bccx, MoveData(), emptyList(), Scope.createNode(body))
}

class GatherLoanContext(
    val bccx: BorrowCheckContext,
    val moveData: MoveData,
    // val moveErrorCollector: MoveErrorCollector,
    val allLoans: List<Loan>,
    val itemUpperBound: Scope
)
