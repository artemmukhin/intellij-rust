/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.infer.BorrowKind
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
    override fun consume(consumeElement: RsElement, cmt: Cmt, mode: ConsumeMode) {
    }

    override fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode) {
    }

    override fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode) {
    }

    override fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, borrowKind: BorrowKind, loancause: LoanCause) {
    }

    override fun declarationWithoutInit(element: RsElement) {
    }

    override fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode) {
    }
}
