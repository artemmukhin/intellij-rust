/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.Downcast
import org.rust.lang.core.types.borrowck.LoanPathKind.Extend
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.infer.MutabilityCategory
import org.rust.lang.core.types.infer.PointerKind
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.ty.Ty

class LoanDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred
    override val initialValue: Boolean = false
}

typealias LoanDataFlow = DataFlowContext<LoanDataFlowOperator>

class AnalysisData(val allLoans: MutableList<Loan>, val loans: LoanDataFlow, val moveData: FlowedMoveData)

class Loan(val index: Int,
           val loanPath: LoanPath,
           val kind: BorrowKind,
           val restrictedPaths: MutableList<LoanPath>,
           val genScope: Scope,  // where loan is introduced
           val killScope: Scope, // where the loan goes out of scope
           val cause: LoanCause
)

class LoanPath(val kind: LoanPathKind, val ty: Ty) {
    val hasDowncast: Boolean
        get() = when {
            kind is Downcast -> true
            kind is Extend && kind.loanPathElement is Interior -> kind.loanPath.hasDowncast
            else -> false
        }
}

sealed class LoanPathKind {
    class Var(val element: RsElement) : LoanPathKind()
    class Upvar : LoanPathKind()
    class Downcast(val loanPath: LoanPath, val element: RsElement) : LoanPathKind()
    class Extend(val loanPath: LoanPath, val mutCategory: MutabilityCategory, val loanPathElement: LoanPathElement) : LoanPathKind()
}

sealed class LoanPathElement {
    class Deref(kind: PointerKind) : LoanPathElement()
    class Interior(element: RsElement?, kind: InteriorKind) : LoanPathElement()
}

enum class LoanCause {
    ClosureCapture,
    AddrOf,
    AutoRef,
    AutoUnsafe,
    RefBinding,
    OverloadedOperator,
    ClosureInvocation,
    ForLoop,
    MatchDiscriminant
}

