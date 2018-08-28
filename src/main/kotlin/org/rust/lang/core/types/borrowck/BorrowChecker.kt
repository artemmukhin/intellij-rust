/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.types.borrowck.LoanPathElement.Deref
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.borrowck.gatherLoans.MoveError
import org.rust.lang.core.types.borrowck.gatherLoans.gatherLoansInFn
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.regions.ScopeTree
import org.rust.lang.core.types.regions.getRegionScopeTree
import org.rust.lang.core.types.ty.Ty


class AnalysisData(val moveData: FlowedMoveData)

data class LoanPath(val kind: LoanPathKind, val ty: Ty) {
    fun killScope(bccx: BorrowCheckContext): Scope =
        when (kind) {
            is Var -> {
                val variable = kind.declaration
                if (variable is RsPatBinding) {
                    bccx.regionScopeTree.getVariableScope(variable) ?: Scope.Node(variable)
                } else {
                    Scope.Node(variable)
                }
            }
            is Downcast -> kind.loanPath.killScope(bccx)
            is Extend -> kind.loanPath.killScope(bccx)
        }

    val element: RsElement?
        get() = when (kind) {
            is LoanPathKind.Var -> kind.use
            is LoanPathKind.Downcast -> kind.element
            is LoanPathKind.Extend -> (kind.loanPath.kind as? LoanPathKind.Var)?.use
        }

    val containingExpr: RsExpr?
        get() = when (kind) {
            is LoanPathKind.Var -> element?.ancestorOrSelf()
            is LoanPathKind.Downcast -> element?.ancestorOrSelf()
            is LoanPathKind.Extend -> element?.parent?.ancestorOrSelf()
        }

    companion object {
        fun computeFor(cmt: Cmt): LoanPath? {
            fun loanPath(kind: LoanPathKind): LoanPath = LoanPath(kind, cmt.ty)

            val category = cmt.category
            return when (category) {
                is Categorization.Rvalue, Categorization.StaticItem -> null

                is Categorization.Local -> loanPath(Var(category.declaration, cmt.element))

                is Categorization.Deref -> {
                    val baseLp = computeFor(category.cmt) ?: return null
                    loanPath(Extend(baseLp, cmt.mutabilityCategory, Deref(category.pointerKind)))
                }

                is Categorization.Interior -> {
                    val baseCmt = category.cmt
                    val baseLp = computeFor(baseCmt) ?: return null
                    val interiorElement = (baseCmt.category as? Categorization.Downcast)?.element
                    val kind = Extend(baseLp, cmt.mutabilityCategory, Interior(interiorElement, category.interiorKind))
                    loanPath(kind)
                }

                is Categorization.Downcast -> {
                    val baseLp = computeFor(category.cmt) ?: return null
                    loanPath(Downcast(baseLp, category.element))
                }

                null -> null
            }
        }
    }
}

sealed class LoanPathKind {
    class Var(val declaration: RsElement, val use: RsElement = declaration) : LoanPathKind() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (declaration != (other as Var).declaration) return false
            return true
        }

        override fun hashCode(): Int {
            return declaration.hashCode()
        }
    }
    data class Downcast(val loanPath: LoanPath, val element: RsElement) : LoanPathKind()
    data class Extend(val loanPath: LoanPath, val mutCategory: MutabilityCategory, val lpElement: LoanPathElement) : LoanPathKind()
}

sealed class LoanPathElement {
    data class Deref(val kind: PointerKind) : LoanPathElement()
    data class Interior(val element: RsElement?, val kind: InteriorKind) : LoanPathElement()
}

class BorrowCheckResult(
    val usesOfMovedValue: List<UseOfMovedValueError>,
    val moveErrors: MutableList<MoveError>
)

class UseOfMovedValueError(val use: RsElement?, val exprWithUse: RsElement?, val move: RsElement?)

class BorrowCheckContext(
    val regionScopeTree: ScopeTree,
    val owner: RsInferenceContextOwner,
    val body: RsBlock,
    val implLookup: ImplLookup = ImplLookup.relativeTo(body),
    val usesOfMovedValue: MutableList<UseOfMovedValueError> = mutableListOf(),
    val moveErrors: MutableList<MoveError> = mutableListOf()
) {
    fun reportUseOfMovedValue(loanPath: LoanPath, move: Move) {
        usesOfMovedValue.add(UseOfMovedValueError(loanPath.element, loanPath.containingExpr, move.element.ancestorOrSelf<RsStmt>()))
    }
}

fun borrowck(owner: RsInferenceContextOwner): BorrowCheckResult? {
    val body = owner.body as? RsBlock ?: return null
    val regionScopeTree = getRegionScopeTree(owner)
    val bccx = BorrowCheckContext(regionScopeTree, owner, body)

    val data = buildBorrowCheckerData(bccx)
    if (data != null) {
        checkLoans(bccx, data.moveData, body)
    }
    return BorrowCheckResult(bccx.usesOfMovedValue, bccx.moveErrors)
}

fun buildBorrowCheckerData(bccx: BorrowCheckContext): AnalysisData? {
    val moveData = gatherLoansInFn(bccx)
    if (moveData.isEmpty()) return null

    val cfg = ControlFlowGraph.buildFor(bccx.body)
    val flowedMoves = FlowedMoveData(moveData, bccx, cfg, bccx.body)
    return AnalysisData(flowedMoves)
}

val RsElement.resolvedElement: RsElement
    get() = when (this) {
        is RsNamedElement -> this
        is RsPath -> this.reference.resolve() ?: this
        is RsPathExpr -> path.resolvedElement
        is RsPatIdent -> patBinding.resolvedElement
        else -> (this.reference as? RsElement)?.resolvedElement ?: this
    }
