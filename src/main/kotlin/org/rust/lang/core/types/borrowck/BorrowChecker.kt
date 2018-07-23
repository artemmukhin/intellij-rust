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
                val variable = kind.element.resolvedElement
                if (variable is RsPatBinding) {
                    bccx.regionScopeTree.getVariableScope(variable) ?: Scope.Node(variable)
                } else {
                    Scope.Node(variable)
                }
            }
            is Upvar -> TODO("not implemented")
            is Downcast -> kind.loanPath.killScope(bccx)
            is Extend -> kind.loanPath.killScope(bccx)
        }

    val containingExpr: RsExpr?
        get() = when (kind) {
            is LoanPathKind.Var -> kind.original?.ancestorOrSelf<RsExpr>()
            is LoanPathKind.Upvar -> null
            is LoanPathKind.Downcast -> kind.element.ancestorOrSelf<RsExpr>()
            is LoanPathKind.Extend -> (kind.loanPath.kind as? LoanPathKind.Var)?.original?.parent?.ancestorOrSelf<RsExpr>()
        }

    companion object {
        fun computeFor(cmt: Cmt): LoanPath? {
            fun loanPath(kind: LoanPathKind): LoanPath = LoanPath(kind, cmt.ty)

            val category = cmt.category
            return when (category) {
                is Categorization.Rvalue, Categorization.StaticItem -> null

                is Categorization.Upvar -> loanPath(Upvar())

                is Categorization.Local -> loanPath(Var(cmt.element.resolvedElement, cmt.element))

                is Categorization.Deref -> {
                    val baseLp = computeFor(category.cmt) ?: return null
                    loanPath(Extend(baseLp, cmt.mutabilityCategory, Deref(category.pointerKind)))
                }

                is Categorization.Interior -> {
                    val baseCmt = category.cmt
                    val baseLp = computeFor(baseCmt) ?: return null
                    val optVariantId = if (baseCmt.category is Categorization.Downcast) baseCmt.element else null
                    val kind = Extend(baseLp, cmt.mutabilityCategory, Interior(optVariantId, category.interiorKind))
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
    class Var(val element: RsElement, val original: RsElement? = null) : LoanPathKind() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (element != (other as Var).element) return false
            return true
        }

        override fun hashCode(): Int {
            return element.hashCode()
        }
    }

    class Upvar : LoanPathKind()
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

class UseOfMovedValueError(val use: RsElement?, val move: RsElement?)

class BorrowCheckContext(
    val regionScopeTree: ScopeTree,
    val owner: RsInferenceContextOwner,
    val body: RsBlock,
    val implLookup: ImplLookup = ImplLookup.relativeTo(body),
    val usesOfMovedValue: MutableList<UseOfMovedValueError> = mutableListOf(),
    val moveErrors: MutableList<MoveError> = mutableListOf()
) {
    fun reportUseOfMovedValue(loanPath: LoanPath, move: Move) {
        usesOfMovedValue.add(UseOfMovedValueError(loanPath.containingExpr, move.element.ancestorOrSelf<RsStmt>()))
    }

}

fun borrowck(owner: RsInferenceContextOwner): BorrowCheckResult? {
    val body = owner.body as? RsBlock ?: return null
    val regionScopeTree = getRegionScopeTree(owner)
    val bccx = BorrowCheckContext(regionScopeTree, owner, body)

    val data = buildBorrowckDataflowData(bccx)
    if (data != null) {
        checkLoans(bccx, data.moveData, body)
    }
    return BorrowCheckResult(bccx.usesOfMovedValue, bccx.moveErrors)
}

fun buildBorrowckDataflowData(bccx: BorrowCheckContext): AnalysisData? {
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
