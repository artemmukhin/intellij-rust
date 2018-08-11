/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.borrowck.ConsumeMode.Copy
import org.rust.lang.core.types.borrowck.ConsumeMode.Move
import org.rust.lang.core.types.borrowck.MoveReason.DirectRefMove
import org.rust.lang.core.types.infer.BorrowKind
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.MemoryCategorizationContext
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.regions.ReEmpty
import org.rust.lang.core.types.regions.ReScope
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyFunction
import org.rust.lang.core.types.type

interface Delegate {
    /** The value found at `cmt` is either copied or moved, depending on mode. */
    fun consume(element: RsElement, cmt: Cmt, mode: ConsumeMode)

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
     * for the region `loanRegion` with kind `kind`.
     */
    fun borrow(element: RsElement, cmt: Cmt, loanRegion: Region, kind: BorrowKind, cause: LoanCause)

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
    object Copy : ConsumeMode()                          // reference to `x` where `x` has a type that copies
    class Move(val reason: MoveReason) : ConsumeMode()   // reference to `x` where x has a type that moves
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
    class Definite(val mode: MatchMode) : TrackMatchMode()
    object Conflicting : TrackMatchMode()

    val matchMode: MatchMode
        get() = when (this) {
            is Unknown -> MatchMode.NonBindingMatch
            is Definite -> mode
            is Conflicting -> MatchMode.MovingMatch
        }

    fun lub(mode: MatchMode) {
        // TODO
    }
}

enum class MutateMode {
    Init,
    JustWrite,      // e.g. `x = y`
    WriteAndRead    // e.g. `x += y`
}

class ExprUseWalker(
    val delegate: Delegate,
    val mc: MemoryCategorizationContext
) {
    fun consumeBody(body: RsBlock) {
        val function = body.parent as? RsFunction ?: return

        for (parameter in function.valueParameters) {
            val parameterType = parameter.typeReference?.type ?: continue
            val parameterPat = parameter.pat ?: continue

            val bodyScopeRegion = ReScope(Scope.createNode(body))
            val parameterCmt = mc.processRvalue(parameter, bodyScopeRegion, parameterType)

            walkIrrefutablePat(parameterCmt, parameterPat)
        }

        walkBlock(body)
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
                expr.containerExpr?.let { selectFromExpr(it) }
                expr.indexExpr?.let { consumeExpr(it) }
            }

            is RsCallExpr -> {
                walkCallee(expr, expr.expr)
                consumeExprs(expr.valueArgumentList.exprList)
            }

            is RsStructLiteral -> {
                walkStructExpr(expr.structLiteralBody.structLiteralFieldList, expr.structLiteralBody.expr)
            }

            is RsTupleExpr -> {
                consumeExprs(expr.exprList)
            }

            is RsIfExpr -> {
                expr.condition?.expr?.let { consumeExpr(it) }
                expr.block?.expr?.let { walkExpr(it) }
                expr.elseBranch?.block?.expr?.let { consumeExpr(it) }
            }

            is RsMatchExpr -> {
                val discriminant = expr.expr ?: return
                val discriminantCmt = mc.processExpr(discriminant)
                val region = ReEmpty
                borrowExpr(discriminant, region, ImmutableBorrow, LoanCause.MatchDiscriminant)

                val arms = expr.matchBody?.matchArmList ?: return
                for (arm in arms) {
                    val mode = armMoveMode(discriminantCmt, arm).matchMode
                    walkArm(discriminantCmt, arm, mode)
                }
            }

            is RsArrayExpr -> consumeExprs(expr.exprList)

            is RsLoopExpr -> expr.block?.let { walkBlock(it) }

            is RsWhileExpr -> {
                expr.condition?.expr?.let { consumeExpr(it) }
                expr.block?.let { walkBlock(it) }
            }

            is RsBinaryExpr -> {
                // TODO: Assign expressions
                consumeExpr(expr.left)
                expr.right?.let { consumeExpr(it) }
            }

            is RsBlockExpr -> walkBlock(expr.block)

        // TODO?
            is RsBreakExpr -> expr.expr?.let { consumeExpr(it) }

            is RsCastExpr -> consumeExpr(expr.expr)

            is RsLambdaExpr -> walkCaptures(expr)
        }
    }

    fun walkCallee(call: RsExpr, callee: RsExpr) {
        val calleeType = callee.type
        when (calleeType) {
            is TyFunction -> consumeExpr(callee)
            else -> {
            } // TODO?
        }
    }

    fun walkStmt(stmt: RsStmt) {
        when (stmt) {
            is RsLetDecl -> walkLet(stmt)
            is RsExprStmt -> consumeExpr(stmt.expr)
        }
    }

    fun walkLet(declaration: RsLetDecl) {
        val init = declaration.expr
        val pat = declaration.pat ?: return
        if (init != null) {
            walkExpr(init)
            val initCmt = mc.processExpr(init)
            walkIrrefutablePat(initCmt, pat)
        } else {
            pat.descendantsOfType<RsPatBinding>().forEach { delegate.declarationWithoutInit(it) }
        }
    }

    fun walkBlock(block: RsBlock) {
        block.stmtList.forEach { walkStmt(it) }
        block.expr?.let { consumeExpr(it) }
    }

    fun walkStructExpr(fields: List<RsStructLiteralField>, withExpr: RsExpr?) {
        fields.mapNotNull { it.expr }.forEach { consumeExpr(it) }

        if (withExpr != null) {
            val withCmt = mc.processExpr(withExpr)
            val withType = withCmt.ty
            if (withType is TyAdt) {
                // TODO
            }
            walkExpr(withExpr)
        }
    }

    fun walkAdjustment(expr: RsExpr) {
        val adjustments = expr.inference?.adjustments?.get(expr) ?: emptyList()
        val cmt = mc.processExprUnadjusted(expr)
        for (adjustment in adjustments) {
            // TODO: overloaded deref support needed
        }
    }

    fun armMoveMode(discriminantCmt: Cmt, arm: RsMatchArm): TrackMatchMode {
        var mode: TrackMatchMode = TrackMatchMode.Unknown
        arm.patList.forEach { pat -> mode = determinePatMoveMode(discriminantCmt, pat, mode) }
        return mode
    }

    fun walkArm(discriminantCmt: Cmt, arm: RsMatchArm, mode: MatchMode) {
        arm.patList.forEach { walkPat(discriminantCmt, it, mode) }
        arm.matchArmGuard?.let { consumeExpr(it.expr) }
        arm.expr?.let { consumeExpr(it) }
    }

    fun walkIrrefutablePat(discriminantCmt: Cmt, pat: RsPat) {
        val mode = determinePatMoveMode(discriminantCmt, pat, TrackMatchMode.Unknown)
        walkPat(discriminantCmt, pat, mode.matchMode)
    }

    // TODO: mc.cat_pattern needed
    fun determinePatMoveMode(discriminantCmt: Cmt, pat: RsPat, mode: TrackMatchMode): TrackMatchMode {
        return TrackMatchMode.Unknown
    }

    // TODO: mc.cat_pattern needed
    fun walkPat(discriminantCmt: Cmt, pat: RsPat, matchMode: MatchMode) {
    }

    // TODO: closures support needed
    fun walkCaptures(closureExpr: RsExpr) {
    }
}

fun copyOrMove(mc: MemoryCategorizationContext, cmt: Cmt, moveReason: MoveReason): ConsumeMode =
    if (mc.isTypeMovesByDefault(cmt.ty)) Move(moveReason) else Copy
