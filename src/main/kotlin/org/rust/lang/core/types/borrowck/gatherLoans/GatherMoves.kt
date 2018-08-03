/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import com.intellij.psi.util.parentOfType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.MoveReason.*
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.ty.Ty

class GatherMoveInfo(
    val element: RsElement,
    val kind: MoveKind,
    val cmt: Cmt,
    val movePlace: MovePlace?
)

sealed class PatternSource {
    class MatchExpr(val expr: RsExpr) : PatternSource()
    class LetDeclaration(val declaration: RsLetDecl) : PatternSource()
    object Other : PatternSource()
}

fun getPatternSource(pat: RsPat): PatternSource {
    val parent = pat.parent
    return when (parent) {
        is RsLetDecl -> PatternSource.LetDeclaration(parent)
        is RsMatchArm -> {
            val matchExpr = parent.parentOfType<RsMatchExpr>()
            PatternSource.MatchExpr(matchExpr!!)
        }
        else -> PatternSource.Other
    }
}

fun gatherDeclaration(bccx: BorrowCheckContext, moveData: MoveData, variable: RsElement, variableType: Ty) {
    val loanPath = LoanPath(LoanPathKind.Var(variable), variableType)
    moveData.addMove(loanPath, variable, MoveKind.Declared)
}

fun gatherMoveFromExpr(
    bccx: BorrowCheckContext,
    moveData: MoveData,
    moveErrorCollector: MoveErrorCollector,
    element: RsElement,
    cmt: Cmt,
    moveReason: MoveReason
) {
    val kind = when (moveReason) {
        DirectRefMove, PatBindingMove -> MoveKind.MoveExpr
        CaptureMove -> MoveKind.Captured
    }

    val moveInfo = GatherMoveInfo(element, kind, cmt, null)
    gatherMove(bccx, moveData, moveErrorCollector, moveInfo)
}

fun gatherMoveFromPat(
    bccx: BorrowCheckContext,
    moveData: MoveData,
    moveErrorCollector: MoveErrorCollector,
    movePat: RsPat,
    cmt: Cmt
) {
    val source = getPatternSource(movePat)
    val patMovePlace = (movePat as? RsPatIdent)?.let { MovePlace(movePat.patBinding.name) }
    val moveInfo = GatherMoveInfo(movePat, MoveKind.MovePat, cmt, patMovePlace)
    gatherMove(bccx, moveData, moveErrorCollector, moveInfo)
}

fun gatherMove(
    bccx: BorrowCheckContext,
    moveData: MoveData,
    moveErrorCollector: MoveErrorCollector,
    moveInfo: GatherMoveInfo
) {
    val move = checkAndGetIllegalMoveOrigin(bccx, moveInfo.cmt)
    if (move != null) {
        val error = MoveError(move, moveInfo.movePlace)
        moveErrorCollector.addError(error)
    } else {
        loanPathIsField(moveInfo.cmt).first?.let { moveData.addMove(it, moveInfo.element, moveInfo.kind) }
    }
}

fun gatherAssignment(
    bccx: BorrowCheckContext,
    moveData: MoveData,
    assignment: RsElement,
    assigneeLoanPath: LoanPath,
    assignee: RsElement,
    mode: MutateMode
) {
    moveData.addAssignment(assigneeLoanPath, assignment, assignee, mode)
}

fun checkAndGetIllegalMoveOrigin(bccx: BorrowCheckContext, cmt: Cmt): Cmt? {
    val category = cmt.category
    return when (category) {
        is Categorization.Rvalue, is Categorization.Local, is Categorization.Upvar -> null
        is Categorization.StaticItem -> cmt
        is Categorization.Deref -> TODO()
        is Categorization.Interior -> TODO()
        is Categorization.Downcast -> TODO()
        null -> TODO()
    }
}

