/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.types.borrowck.BorrowCheckContext
import org.rust.lang.core.types.borrowck.Loan
import org.rust.lang.core.types.borrowck.MoveData

fun gatherLoansInFn(context: BorrowCheckContext, body: RsBlock): Pair<List<Loan>, MoveData> {}
