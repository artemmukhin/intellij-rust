/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.RsPatIdent
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.lang.core.psi.ext.RsStructOrEnumItemElement
import org.rust.lang.core.types.borrowck.*
import org.rust.lang.core.types.borrowck.MoveReason.*
import org.rust.lang.core.types.infer.Categorization
import org.rust.lang.core.types.infer.Cmt
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.infer.InteriorKind.InteriorField
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TySlice

class MoveError(val from: Cmt, val to: MovePlace?)
class MovePlace(val name: RsNamedElement)

class GatherMoveInfo(
    val element: RsElement,
    val kind: MoveKind,
    val cmt: Cmt,
    val movePlace: MovePlace?
)

class GatherMoveContext(val bccx: BorrowCheckContext) {
    fun gatherDeclaration(moveData: MoveData, variable: RsElement, variableType: Ty) {
        val loanPath = LoanPath(LoanPathKind.Var(variable), variableType)
        moveData.addMove(loanPath, variable, MoveKind.Declared)
    }

    fun gatherMoveFromExpr(moveData: MoveData, element: RsElement, cmt: Cmt, moveReason: MoveReason) {
        val kind = when (moveReason) {
            DirectRefMove, PatBindingMove -> MoveKind.MoveExpr
            CaptureMove -> MoveKind.Captured
        }

        val moveInfo = GatherMoveInfo(element, kind, cmt, null)
        gatherMove(moveData, moveInfo)
    }

    fun gatherMoveFromPat(moveData: MoveData, movePat: RsPat, cmt: Cmt) {
        val patMovePlace = (movePat as? RsPatIdent)?.let { MovePlace(movePat.patBinding) }
        val moveInfo = GatherMoveInfo(movePat, MoveKind.MovePat, cmt, patMovePlace)
        gatherMove(moveData, moveInfo)
    }

    private fun gatherMove(moveData: MoveData, moveInfo: GatherMoveInfo) {
        val move = checkAndGetIllegalMoveOrigin(moveInfo.cmt)
        if (move != null) {
            val error = MoveError(move, moveInfo.movePlace)
            bccx.moveErrors.add(error)
        } else {
            LoanPath.computeFor(moveInfo.cmt)?.let { moveData.addMove(it, moveInfo.element, moveInfo.kind) }
        }
    }

    fun gatherAssignment(moveData: MoveData, assignmentData: AssignmentData, mode: MutateMode) {
        moveData.addAssignment(assignmentData.loanPath, assignmentData.assignment, assignmentData.assignee, mode)
    }

    // TODO: refactor
    private fun checkAndGetIllegalMoveOrigin(cmt: Cmt): Cmt? {
        val category = cmt.category
        return when (category) {
            is Categorization.Rvalue, is Categorization.Local -> null

            is Categorization.StaticItem, is Categorization.Deref -> cmt

            is Categorization.Interior -> {
                val kind = category.interiorKind
                when {
                    kind is InteriorField || (kind is InteriorKind.InteriorPattern) -> {
                        val base = category.cmt
                        when (base.ty) {
                            is TyAdt -> if (base.ty.item.hasDestructor) cmt else checkAndGetIllegalMoveOrigin(base)
                            is TySlice -> cmt
                            else -> checkAndGetIllegalMoveOrigin(base)
                        }
                    }
                    kind is InteriorKind.InteriorIndex -> cmt
                    else -> cmt
                }
            }

            is Categorization.Downcast -> {
                val base = category.cmt
                when (base.ty) {
                    is TyAdt -> if (base.ty.item.hasDestructor) cmt else checkAndGetIllegalMoveOrigin(base)
                    is TySlice -> cmt
                    else -> checkAndGetIllegalMoveOrigin(base)
                }
            }

            null -> null
        }
    }
}

// TODO: use ImplLookup
val RsStructOrEnumItemElement.hasDestructor: Boolean get() = false
