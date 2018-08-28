/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.ext.RsBindingModeKind.BindByReference
import org.rust.lang.core.psi.ext.RsBindingModeKind.BindByValue
import org.rust.lang.core.types.borrowck.ConsumeMode.Copy
import org.rust.lang.core.types.borrowck.ConsumeMode.Move
import org.rust.lang.core.types.borrowck.LoanCause.*
import org.rust.lang.core.types.borrowck.MatchMode.CopyingMatch
import org.rust.lang.core.types.borrowck.MatchMode.NonBindingMatch
import org.rust.lang.core.types.borrowck.MoveReason.DirectRefMove
import org.rust.lang.core.types.borrowck.MoveReason.PatBindingMove
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.regions.ReEmpty
import org.rust.lang.core.types.regions.ReScope
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.ty.Mutability
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyFunction
import org.rust.lang.core.types.ty.TyReference
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

    val matchMode: MatchMode
        get() = when (this) {
            is Copy -> MatchMode.CopyingMatch
            is Move -> MatchMode.MovingMatch
        }
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

    fun lub(mode: MatchMode): TrackMatchMode =
        when {
            this is Unknown -> Definite(mode)
            this is Definite && this.mode == mode -> this
            this is Definite && mode == NonBindingMatch -> this
            this is Definite && this.mode == NonBindingMatch -> Definite(mode)
            this is Definite && mode == CopyingMatch -> this
            this is Definite && this.mode == CopyingMatch -> Definite(mode)
            this is Definite -> Conflicting
            this is Conflicting -> this
            else -> this
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
        fun walkSelfParameter(selfParameter: RsSelfParameter) {
            val type = selfParameter.typeReference?.type as? TyReference ?: return
            val mutability = selfParameter.mutability

            val mutabilityCategory = MutabilityCategory.from(mutability)
            val cmt = Cmt(selfParameter, Categorization.Local(selfParameter, selfParameter), mutabilityCategory, type)

            if (selfParameter.isRef) {
                delegate.borrow(selfParameter, cmt, type.region, BorrowKind.from(mutability), RefBinding)
            }
        }

        val function = body.parent as? RsFunction ?: return

        function.selfParameter?.let { walkSelfParameter(it) }

        for (parameter in function.valueParameters) {
            val parameterType = parameter.typeReference?.type ?: continue
            val parameterPat = parameter.pat ?: continue

            val bodyScopeRegion = ReScope(Scope.Node(body))
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
        walkExpr(expr)
    }

    private fun mutateExpr(assignmentExpr: RsExpr, expr: RsExpr, mode: MutateMode) {
        val cmt = mc.processExpr(expr)
        delegate.mutate(assignmentExpr, cmt, mode)
        walkExpr(expr)
    }

    private fun borrowExpr(expr: RsExpr, region: Region, borrowKind: BorrowKind, cause: LoanCause) {
        val cmt = mc.processExpr(expr)
        delegate.borrow(expr, cmt, region, borrowKind, cause)
        walkExpr(expr)
    }

    private fun selectFromExpr(expr: RsExpr) =
        walkExpr(expr)

    private fun walkExpr(expr: RsExpr) {
        walkAdjustment(expr)

        when (expr) {
            is RsUnaryExpr -> {
                val base = expr.expr ?: return
                if (expr.mul != null) {
                    selectFromExpr(base)
                } else if (expr.and != null) {
                    val exprType = expr.type as? TyReference ?: return
                    val mutability = Mutability.valueOf(expr.mut != null)
                    borrowExpr(base, exprType.region, BorrowKind.from(mutability), AddrOf)
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
                expr.condition?.let { walkCondition(it) }
                expr.block?.let { walkBlock(it) }
                expr.elseBranch?.block?.let { walkBlock(it) } // TODO: is it right, or maybe consume else branch?
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
                expr.condition?.let { walkCondition(it) }
                expr.block?.let { walkBlock(it) }
            }

            is RsBinaryExpr -> {
                val left = expr.left
                val right = expr.right ?: return
                val operator = expr.binaryOp.operatorType
                when (operator) {
                    is AssignmentOp -> mutateExpr(expr, left, MutateMode.JustWrite)
                    is ArithmeticAssignmentOp -> mutateExpr(expr, left, MutateMode.WriteAndRead)
                    else -> consumeExpr(left)
                }
                consumeExpr(right)
            }

            is RsBlockExpr -> walkBlock(expr.block)

            is RsBreakExpr -> expr.expr?.let { consumeExpr(it) }

            is RsRetExpr -> expr.expr?.let { consumeExpr(it) }

            is RsCastExpr -> consumeExpr(expr.expr)

            is RsLambdaExpr -> walkCaptures(expr)
        }
    }

    fun walkCallee(call: RsExpr, callee: RsExpr) {
        if (callee.type is TyFunction) consumeExpr(callee)
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

    fun walkCondition(condition: RsCondition) {
        val init = condition.expr
        walkExpr(init)
        val initCmt = mc.processExpr(init)
        condition.pat?.let { walkIrrefutablePat(initCmt, it) }
    }

    private fun walkBlock(block: RsBlock) {
        block.stmtList.forEach { walkStmt(it) }
        block.expr?.let { consumeExpr(it) }
    }

    private fun walkStructExpr(fields: List<RsStructLiteralField>, withExpr: RsExpr?) {
        fields.mapNotNull { it.expr }.forEach { consumeExpr(it) }
        if (withExpr == null) return

        val withCmt = mc.processExpr(withExpr)
        val withType = withCmt.ty
        if (withType is TyAdt) {
            val structFields = (withType.item as? RsStructItem)?.namedFields ?: emptyList()
            for (field in structFields) {
                // TODO: use field index instead of identifier
                val isMentioned = fields.any { it.identifier.text == field.identifier.text }
                if (!isMentioned) {
                    val interior = Categorization.Interior(withCmt, InteriorKind.InteriorField(field.name))
                    val fieldCmt = Cmt(withExpr, interior, withCmt.mutabilityCategory.inherit(), withType)
                    delegateConsume(withExpr, fieldCmt)
                }
            }
        }

        walkExpr(withExpr)
    }

    private fun walkAdjustment(expr: RsExpr) {
        val adjustments = expr.inference?.adjustments?.get(expr) ?: emptyList()
        val cmt = mc.processExprUnadjusted(expr)
        for (adjustment in adjustments) {
            when (adjustment) {
                is Adjustment.Deref -> {
                    // TODO: overloaded deref support needed
                }
                is Adjustment.BorrowReference -> {
                    val region = adjustment.region.value
                    val mutability = adjustment.mutability.value
                    if (region != null && mutability != null) {
                        delegate.borrow(expr, cmt, region, BorrowKind.from(mutability), AutoRef)
                    }
                }
                is Adjustment.BorrowPointer -> {
                    // TODO
                }
            }
        }
    }

    private fun armMoveMode(discriminantCmt: Cmt, arm: RsMatchArm): TrackMatchMode {
        var mode: TrackMatchMode = TrackMatchMode.Unknown
        arm.patList.forEach { mode = determinePatMoveMode(discriminantCmt, it, mode) }
        return mode
    }

    private fun walkArm(discriminantCmt: Cmt, arm: RsMatchArm, mode: MatchMode) {
        arm.patList.forEach { walkPat(discriminantCmt, it, mode) }
        arm.matchArmGuard?.let { consumeExpr(it.expr) }
        arm.expr?.let { consumeExpr(it) }
    }

    private fun walkIrrefutablePat(discriminantCmt: Cmt, pat: RsPat) {
        val mode = determinePatMoveMode(discriminantCmt, pat, TrackMatchMode.Unknown)
        walkPat(discriminantCmt, pat, mode.matchMode)
    }

    /** Identifies any bindings within [pat] whether the overall pattern/match structure is a move, copy, or borrow */
    private fun determinePatMoveMode(discriminantCmt: Cmt, pat: RsPat, mode: TrackMatchMode): TrackMatchMode {
        var newMode = mode
        mc.walkPat(discriminantCmt, pat) { patCmt, pat ->
            if (pat is RsPatIdent && pat.patBinding.reference.resolve() !is RsEnumVariant) {
                newMode = when (pat.patBinding.kind) {
                    is BindByReference -> newMode.lub(MatchMode.BorrowingMatch)
                    is BindByValue -> newMode.lub(copyOrMove(mc, patCmt, PatBindingMove).matchMode)
                }
            }
        }

        return newMode
    }

    /**
     * The core driver for walking a pattern; [matchMode] must be established up front, e.g. via [determinePatMoveMode]
     * (see also [walkIrrefutablePat] for patterns that stand alone)
     */
    private fun walkPat(discriminantCmt: Cmt, pat: RsPat, matchMode: MatchMode) {
        mc.walkPat(discriminantCmt, pat) { patCmt, pat ->
            if (pat is RsPatIdent && pat.patBinding.reference.resolve() !is RsEnumVariant) {
                val patType = pat.patBinding.type

                // TODO: get definition of pat
                /*
                let def = Def::Local(canonical_id);
                if let Ok(ref binding_cmt) = mc.cat_def(pat.id, pat.span, pat_ty, def) {
                    delegate.mutate(pat.id, pat.span, binding_cmt, MutateMode::Init);
                }
                */

                val bindingCmt = mc.processDef(pat, patType)
                // Each match binding is effectively an assignment to the binding being produced.
                if (bindingCmt != null) {
                    delegate.mutate(pat, bindingCmt, MutateMode.Init)
                }

                // It is also a borrow or copy/move of the value being matched.
                val kind = pat.patBinding.kind
                if (kind is BindByReference && patType is TyReference) {
                    delegate.borrow(pat, patCmt, patType.region, BorrowKind.from(kind.mutability), RefBinding)
                } else if (kind is BindByValue) {
                    delegate.consumePat(pat, patCmt, copyOrMove(mc, patCmt, PatBindingMove))
                }
            }
        }
    }

    // TODO: closures support needed
    private fun walkCaptures(closureExpr: RsExpr) {}
}

fun copyOrMove(mc: MemoryCategorizationContext, cmt: Cmt, moveReason: MoveReason): ConsumeMode =
    if (mc.isTypeMovesByDefault(cmt.ty)) Move(moveReason) else Copy
