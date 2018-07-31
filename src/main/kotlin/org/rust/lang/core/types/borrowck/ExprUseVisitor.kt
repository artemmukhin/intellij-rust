/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.regions.Region

interface Delegate {
    /** The value found at `cmt` is either copied or moved, depending on mode. */
    fun consume(consumeElement: RsElement, cmt: Cmt, mode: ConsumeMode)

    /**
     * The value found at `cmt` has been determined to match the
     * pattern binding `pat`, and its subparts are being
     * copied or moved depending on `mode`.  Note that `matchedPat`
     * is called on all variant/structs in the pattern (i.e., the
     * interior nodes of the pattern's tree structure) while
     * consumePat is called on the binding identifiers in the pattern
     * (which are leaves of the pattern's tree structure).
     */
    fun matchedPat(pat: RsPat, cmt: Cmt, mode: MatchMode)

    /**
     * The value found at `cmt` is either copied or moved via the
     * pattern binding `consumePat`, depending on mode.
     */
    fun consumePat(pat: RsPat, cmt: Cmt, mode: ConsumeMode)

    /**
     * The value found at `borrow` is being borrowed at the point `element`
     * for the region `loanRegion` with kind `borrowKind`.
     */
    fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, borrowKind: BorrowKind, loancause: LoanCause)

    /** The local variable `element` is declared but not initialized. */
    fun declarationWithoutInit(element: RsElement)

    /** The path at `cmt` is being assigned to. */
    fun mutate(assignmentElement: RsElement, assigneeCmt: Cmt, mode: MutateMode)

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

sealed class ConsumeMode {
    object Copy : ConsumeMode()                     // reference to `x` where `x` has a type that copies
    class Move(reason: MoveReason) : ConsumeMode()   // reference to `x` where x has a type that moves
}

enum class MoveReason {
    DirectRefMove,
    PatBindingMove,
    CaptureMove
}

enum class MatchMode {
    NonBindingMatch,
    BorrowingMatch,
    CopyingMatch,
    MovingMatch,
}

sealed class TrackMatchMode {
    object Unknown : TrackMatchMode()
    class Definite(mode: MatchMode) : TrackMatchMode()
    object Conflicting : TrackMatchMode()
}

enum class MutateMode {
    Init,
    JustWrite,      // e.g. `x = y`
    WriteAndRead    // e.g. `x += y`
}

class ExprUseVisitor(
    val mc: MemoryCategorizationContext,
    val delegate: Delegate
)
