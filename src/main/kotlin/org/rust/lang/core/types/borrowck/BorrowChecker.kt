/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
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
import org.rust.lang.core.types.infer.outlives.FreeRegionMap
import org.rust.lang.core.types.regions.*
import org.rust.lang.core.types.ty.Ty

object LoanDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred
    override val initialValue: Boolean = false
}

typealias LoanDataFlow = DataFlowContext<LoanDataFlowOperator>

class AnalysisData(val allLoans: List<Loan>, val loans: LoanDataFlow, val moveData: FlowedMoveData)

class Loan(val index: Int,
           val loanPath: LoanPath,
           val kind: BorrowKind,
           val restrictedPaths: MutableList<LoanPath>,
           val genScope: Scope,  // where loan is introduced
           val killScope: Scope, // where the loan goes out of scope
           val cause: LoanCause
)

data class LoanPath(val kind: LoanPathKind, val ty: Ty) {
    val hasDowncast: Boolean
        get() = when {
            kind is Downcast -> true
            kind is Extend && kind.lpElement is Interior -> kind.loanPath.hasDowncast
            else -> false
        }

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

    fun hasFork(other: LoanPath): Boolean {
        val thisKind = this.kind
        val otherKind = other.kind
        return when {
            thisKind is Extend && otherKind is Extend && thisKind.lpElement is Interior && otherKind.lpElement is Interior ->
                if (thisKind.lpElement == otherKind.lpElement) {
                    thisKind.loanPath.hasFork(otherKind.loanPath)
                } else {
                    true
                }

            thisKind is Extend && thisKind.lpElement is Deref -> thisKind.loanPath.hasFork(other)

            otherKind is Extend && otherKind.lpElement is Deref -> this.hasFork(otherKind.loanPath)

            else -> false
        }
    }

    val containingExpr: RsExpr?
        get() = when (kind) {
            is LoanPathKind.Var -> kind.original?.ancestorOrSelf<RsExpr>()
            is LoanPathKind.Upvar -> null
            is LoanPathKind.Downcast -> kind.element.ancestorOrSelf<RsExpr>()
            is LoanPathKind.Extend -> (kind.loanPath.kind as? LoanPathKind.Var)?.original?.parent?.ancestorOrSelf<RsExpr>()
        }

    val containingStmtText: String?
        get() = when (kind) {
            is LoanPathKind.Var -> kind.original?.ancestorOrSelf<RsStmt>()?.text
            is LoanPathKind.Upvar -> ""
            is LoanPathKind.Downcast -> kind.element.ancestorOrSelf<RsStmt>()?.text
            is LoanPathKind.Extend -> {
                val baseText = (kind.loanPath.kind as? LoanPathKind.Var)?.original?.text
                val fieldText = ((kind.lpElement as? Interior)?.kind as InteriorKind.InteriorField).fieldName
                "$baseText.$fieldText"
            }
        }

    companion object {
        fun computeFor(cmt: Cmt): LoanPath? =
            computeAndCheckIfField(cmt).first

        // TODO: refactor
        fun computeAndCheckIfField(cmt: Cmt): Pair<LoanPath?, Boolean> {
            fun loanPath(kind: LoanPathKind): LoanPath = LoanPath(kind, cmt.ty)

            val category = cmt.category
            return when (category) {
                is Categorization.Rvalue, Categorization.StaticItem -> Pair(null, false)

                is Categorization.Upvar -> Pair(loanPath(Upvar()), false)

                is Categorization.Local -> Pair(loanPath(Var(cmt.element.resolvedElement, cmt.element)), false)

                is Categorization.Deref -> {
                    val (baseLp, baseIsField) = computeAndCheckIfField(category.cmt)
                    if (baseLp != null) {
                        val kind = Extend(baseLp, cmt.mutabilityCategory, Deref(category.pointerKind))
                        Pair(loanPath(kind), baseIsField)
                    } else {
                        Pair(null, baseIsField)
                    }
                }

                is Categorization.Interior -> {
                    val baseCmt = category.cmt
                    val baseLp = computeFor(baseCmt) ?: return Pair(null, true)
                    val optVariantId = if (baseCmt.category is Categorization.Downcast) baseCmt.element else null
                    val kind = Extend(baseLp, cmt.mutabilityCategory, Interior(optVariantId, category.interiorKind))
                    Pair(loanPath(kind), true)
                }

                is Categorization.Downcast -> {
                    val baseCmt = category.cmt
                    val (baseLp, baseIsField) = computeAndCheckIfField(baseCmt)
                    if (baseLp != null) {
                        val kind = Downcast(baseLp, category.element)
                        Pair(loanPath(kind), baseIsField)
                    } else {
                        Pair(null, baseIsField)
                    }
                }

                null -> Pair(null, false)
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
    val usedMutNodes: MutableSet<RsElement>,
    val usesOfMovedValue: List<UseOfMovedValueError>,
    val moveErrors: MutableList<MoveError>
)

class UseOfMovedValueError(val use: RsElement?, val move: RsElement?)

class BorrowCheckContext(
    val regionScopeTree: ScopeTree,
    val owner: RsInferenceContextOwner,
    val body: RsBlock,
    val implLookup: ImplLookup = ImplLookup.relativeTo(body),
    val usedMutNodes: MutableSet<RsElement> = mutableSetOf(),
    val usesOfMovedValue: MutableList<UseOfMovedValueError> = mutableListOf(),
    val moveErrors: MutableList<MoveError> = mutableListOf()
) {
    fun isSubRegionOf(sub: Region, sup: Region): Boolean {
        val freeRegions = FreeRegionMap() // TODO
        val regionRelations = RegionRelations(owner, regionScopeTree, freeRegions)
        return regionRelations.isSubRegionOf(sub, sup)
    }

    fun report(error: BorrowCheckError) {
        println("###report: ")
        when (error.code) {
            is BorrowCheckErrorCode.Mutability -> println("Cannot borrow immutable as mutable")
            is BorrowCheckErrorCode.OutOfScope -> println("Out of scope")
            is BorrowCheckErrorCode.BorrowedPointerTooShort -> println("Borrowed pointer too short")
        }
        println()
    }

    fun reportAliasabilityViolation(cause: AliasableViolationKind, reason: AliasableReason, cmt: Cmt) {
        print("###reportAliasabilityViolation: ")
        println(cause)
        println()
    }

    fun reportUseOfMovedValue(useKind: MovedValueUseKind, loanPath: LoanPath, move: Move, movedLp: LoanPath) {
        /*
        println("###reportUseOfMovedValue###")
        println("Use of moved value: ${loanPath.containingStmtText}")
        println("Move happened at: ${move.element.ancestorOrSelf<RsStmt>()?.text}")
        println()
        */
        usesOfMovedValue.add(UseOfMovedValueError(loanPath.containingExpr, move.element.ancestorOrSelf<RsStmt>()))
    }

    // TODO: false positives
    fun reportReassignedImmutableVariable(loanPath: LoanPath, assignment: Assignment) {
        print("###reportReassignedImmutableVariable: ")
        println(loanPath.kind)
    }
}

fun borrowck(owner: RsInferenceContextOwner): BorrowCheckResult? {
    val body = owner.body as? RsBlock ?: return null
    val regionScopeTree = getRegionScopeTree(owner)
    val bccx = BorrowCheckContext(regionScopeTree, owner, body)

    val data = buildBorrowckDataflowData(bccx, false, body)
    if (data != null) {
        checkLoans(bccx, data.loans, data.moveData, data.allLoans, body)
        // TODO: implement and call `unusedCheck(borrowCheckContext, body)`
    }
    return BorrowCheckResult(bccx.usedMutNodes, bccx.usesOfMovedValue, bccx.moveErrors)
}

fun buildBorrowckDataflowData(bccx: BorrowCheckContext, forceAnalysis: Boolean, body: RsBlock): AnalysisData? {
    val (allLoans, moveData) = gatherLoansInFn(bccx, body)
    if (!forceAnalysis && allLoans.isEmpty() && moveData.isEmpty()) return null

    val cfg = ControlFlowGraph.buildFor(body)
    val loanDfcx = DataFlowContext(body, cfg, LoanDataFlowOperator, allLoans.size)

    allLoans.forEachIndexed { i, loan ->
        loanDfcx.addGen(loan.genScope.element, i)
        loanDfcx.addKill(KillFrom.ScopeEnd, loan.killScope.element, i)
    }
    loanDfcx.addKillsFromFlowExits()
    loanDfcx.propagate()

    val flowedMoves = FlowedMoveData(moveData, bccx, cfg, body)
    return AnalysisData(allLoans, loanDfcx, flowedMoves)
}

sealed class AliasableViolationKind {
    object MutabilityViolation : AliasableViolationKind()
    class BorrowViolation(val cause: LoanCause) : AliasableViolationKind()
}

enum class MovedValueUseKind {
    MovedInUse,
    MovedInCapture
}

sealed class BorrowCheckErrorCode {
    object Mutability : BorrowCheckErrorCode()
    class OutOfScope(val superScope: Region, val subScope: Region, val loanCause: LoanCause) : BorrowCheckErrorCode()
    class BorrowedPointerTooShort(val loanRegion: Region, val pointerRegion: Region) : BorrowCheckErrorCode()
}

class BorrowCheckError(
    val cause: AliasableViolationKind,
    val cmt: Cmt,
    val code: BorrowCheckErrorCode
)

val RsElement.resolvedElement: RsElement
    get() = when (this) {
        is RsNamedElement -> this
        is RsPath -> this.reference.resolve() ?: this
        is RsPathExpr -> path.resolvedElement
        is RsPatIdent -> patBinding.resolvedElement
        else -> (this.reference as? RsElement)?.resolvedElement ?: this
    }
