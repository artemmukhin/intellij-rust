/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.containerExpr
import org.rust.lang.core.psi.ext.indexExpr
import org.rust.lang.core.psi.ext.valueParameters
import org.rust.lang.core.types.borrowck.ConsumeMode.Copy
import org.rust.lang.core.types.borrowck.ConsumeMode.Move
import org.rust.lang.core.types.borrowck.MoveReason.DirectRefMove
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.regions.ReScope
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.type

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
) {
    fun consumeBody(body: RsBlock) {
        val function = body.parent as? RsFunction ?: return

        for (parameter in function.valueParameters) {
            val parameterType = parameter.typeReference?.type ?: continue

            val bodyScopeRegion = ReScope(Scope.createNode(body))
            val parameterCmt = mc.processRvalue(parameter, bodyScopeRegion, parameterType)

            walkIrrefutablePat(parameterCmt, parameter.pat)
        }

        consumeExpr(body)
    }

    fun delegateConsume(element: RsElement, cmt: Cmt) {
        val mode = copyOrMove(mc, cmt, DirectRefMove)
        delegate.consume(element, cmt, mode)
    }

    fun consumeExprs(exprs: List<RsExpr>) =
        exprs.forEach { consumeExpr(it) }

    fun consumeExpr(expr: RsExpr) {
        val cmt = mc.processExpr(expr)
        delegateConsume(expr, cmt)
    }

    fun mutateExpr(assignmentExpr: RsExpr, expr: RsExpr, mode: MutateMode) {
        val cmt = mc.processExpr(expr)
        delegate.mutate(assignmentExpr, cmt, mode)
        walkExpr(expr)
    }

    fun borrowExpr(expr: RsExpr, region: Region, borrowKind: BorrowKind, cause: LoanCause) {
        val cmt = mc.processExpr(expr)
        delegate.borrow(expr, cmt, region, borrowKind, cause)
        walkExpr(expr)
    }

    fun selectFromExpr(expr: RsExpr) =
        walkExpr(expr)

    fun walkExpr(expr: RsExpr) {
        walkAdjustment(expr)

        when (expr) {
            is RsPathExpr -> {
            }

            is RsUnaryExpr -> {
                val base = expr.expr ?: return
                if (expr.mul != null) {
                    selectFromExpr(base)
                } else {
                    consumeExpr(base)
                }
            }

            is RsDotExpr -> {
                val base = expr.expr
                val fieldLookup = expr.fieldLookup
                val methodCall = expr.methodCall

                if (fieldLookup != null) {
                    selectFromExpr(base)
                } else if (methodCall != null) {
                    consumeExprs(methodCall.valueArgumentList.exprList)
                }
            }

            is RsIndexExpr -> {
                val containerExpr = expr.containerExpr ?: return
                val indexExpr = expr.indexExpr ?: return
                selectFromExpr(containerExpr)
                consumeExpr(indexExpr)
            }

            is RsCallExpr -> {
                walkCalee(expr, expr.expr)
                consumeExprs(expr.valueArgumentList.exprList)
            }
        }
    }
}

fun copyOrMove(mc: MemoryCategorizationContext, cmt: Cmt, moveReason: MoveReason): ConsumeMode =
    if (mc.isTypeMovesByDefault(cmt.ty)) Move(moveReason) else Copy
