/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.isLazy
import java.util.*

class CFGBuilder(val graph: Graph<CFGNode, CFGEdge>, val entry: NodeIndex, val exit: NodeIndex) : RsVisitor() {
    class BlockScope(val block: RsBlock, val breakIndex: NodeIndex)

    class LoopScope(val loop: RsExpr, val continueIndex: NodeIndex, val breakIndex: NodeIndex)

    enum class ScopeControlFlowKind { BREAK, CONTINUE }

    data class Destination(val label: RsLabel?, val target: RsElement)


    private var result: NodeIndex? = null
    private val preds: Deque<NodeIndex> = ArrayDeque<NodeIndex>()
    private val pred: NodeIndex get() = preds.peek()
    private val loopScopes: Deque<LoopScope> = ArrayDeque<LoopScope>()
    private val breakableBlockScopes: Deque<BlockScope> = ArrayDeque<BlockScope>()

    private inline fun finishWith(callable: () -> NodeIndex) { result = callable() }

    private fun finishWith(value: NodeIndex) { result = value }

    private fun withLoopScope(loopScope: LoopScope, callable: () -> Unit) {
        loopScopes.push(loopScope)
        callable()
        loopScopes.pop()
    }

    private fun addAstNode(element: RsElement, preds: List<NodeIndex>): NodeIndex = addNode(CFGNode.AST(element), preds)

    private fun addDummyNode(preds: List<NodeIndex>): NodeIndex = addNode(CFGNode.Dummy, preds)

    private fun addUnreachableNode(): NodeIndex = addNode(CFGNode.Unreachable, emptyList())

    private fun addNode(node: CFGNode, preds: List<NodeIndex>): NodeIndex {
        val newNode = graph.addNode(node)
        preds.forEach { addContainedEdge(it, newNode) }
        return newNode
    }

    fun addContainedEdge(source: NodeIndex, target: NodeIndex) {
        val data = CFGEdge(mutableListOf())
        graph.addEdge(source, target, data)
    }

    private fun addReturningEdge(fromIndex: NodeIndex) {
        val edge = CFGEdge(loopScopes.map { it.loop }.toMutableList())
        graph.addEdge(fromIndex, exit, edge)
    }

    private fun straightLine(expr: RsExpr, pred: NodeIndex, subExprs: List<RsExpr?>): NodeIndex {
        val subExprsExit = subExprs.fold(pred) { pred, subExpr -> process(subExpr, pred) }
        return addAstNode(expr, listOf(subExprsExit))
    }

    // todo: return (Region.Scope, NodeIndex)
    private fun findScopeEdge(expr: RsExpr, destination: Destination, kind: ScopeControlFlowKind): NodeIndex? {
        for (b in breakableBlockScopes) {
            if (b.block == destination.target) {
                return when (kind) {
                    ScopeControlFlowKind.BREAK -> b.breakIndex
                    ScopeControlFlowKind.CONTINUE -> null
                }
            }
        }
        for (l in loopScopes) {
            if (l.loop == destination.target) {
                return when (kind) {
                    ScopeControlFlowKind.BREAK -> l.breakIndex
                    ScopeControlFlowKind.CONTINUE -> l.continueIndex
                }
            }
        }

        return null
    }

    fun process(element: RsElement?, pred: NodeIndex): NodeIndex {
        if (element == null) return pred

        val oldPredsSize = preds.size
        preds.push(pred)
        element.accept(this)
        preds.pop()
        assert(preds.size == oldPredsSize)

        return result!!
    }

    private fun allPats(pat: RsPat, subPats: List<RsPat?>): NodeIndex {
        val patsExit = subPats.fold(pred) { pred, subPat -> process(subPat, pred) }
        return addAstNode(pat, listOf(patsExit))
    }

    private fun processCall(callExpr: RsExpr, pred: NodeIndex, func: RsExpr?, args: List<RsExpr?>): NodeIndex {
        val funcExit = process(func, pred)
        return straightLine(callExpr, funcExit, args)
    }


    override fun visitBlock(block: RsBlock) {
        val stmtsExit = block.stmtList.fold(pred) { pred, stmt -> process(stmt, pred) }
        val blockExpr = block.expr
        val exprExit = process(blockExpr, stmtsExit)

        finishWith { addAstNode(block, listOf(exprExit)) }
    }

    override fun visitLetDecl(letDecl: RsLetDecl) {
        val initExit = process(letDecl.expr, pred)
        val exit = process(letDecl.pat, initExit)

        finishWith { addAstNode(letDecl, listOf(exit)) }
    }

    override fun visitFieldDecl(fieldDecl: RsFieldDecl) = finishWith(pred)

    override fun visitLabelDecl(labelDecl: RsLabelDecl) = finishWith(pred)

    override fun visitExprStmt(exprStmt: RsExprStmt) {
        val exit = process(exprStmt.expr, pred)
        finishWith { addAstNode(exprStmt, listOf(exit)) }
    }

    override fun visitPatBinding(patBinding: RsPatBinding) =
        finishWith { addAstNode(patBinding, listOf(pred)) }

    override fun visitPatIdent(patIdent: RsPatIdent) =
        finishWith { addAstNode(patIdent, listOf(pred)) }

    override fun visitPatRange(patRange: RsPatRange) =
        finishWith { addAstNode(patRange, listOf(pred)) }

    override fun visitPatConst(patConst: RsPatConst) =
        finishWith { addAstNode(patConst, listOf(pred)) }

    override fun visitPatWild(patWild: RsPatWild) =
        finishWith { addAstNode(patWild, listOf(pred)) }

    override fun visitPathExpr(pathExpr: RsPathExpr) =
        finishWith { addAstNode(pathExpr, listOf(pred)) }

    override fun visitRangeExpr(rangeExpr: RsRangeExpr) =
        finishWith { addAstNode(rangeExpr, listOf(pred)) }

    override fun visitPatTup(patTup: RsPatTup) =
        finishWith { allPats(patTup, patTup.patList) }

    override fun visitPatTupleStruct(patTupleStruct: RsPatTupleStruct) =
        finishWith { allPats(patTupleStruct, patTupleStruct.patList) }

    override fun visitPatStruct(patStruct: RsPatStruct) =
        finishWith { allPats(patStruct, patStruct.patFieldList.map { it.pat }) }

    override fun visitPatSlice(patSlice: RsPatSlice) =
        finishWith { allPats(patSlice, patSlice.patList) }

    override fun visitBlockExpr(blockExpr: RsBlockExpr) {
        val blockExit = process(blockExpr.block, pred)
        finishWith { addAstNode(blockExpr, listOf(blockExit)) }
    }

    override fun visitIfExpr(ifExpr: RsIfExpr) {
        val conditionExit = process(ifExpr.condition?.expr, pred)
        val thenExit = process(ifExpr.block, conditionExit)
        val elseBranch = ifExpr.elseBranch

        if (elseBranch != null) {
            val elseExit = process(elseBranch.block, conditionExit)
            finishWith { addAstNode(ifExpr, listOf(thenExit, elseExit)) }
        } else {
            finishWith { addAstNode(ifExpr, listOf(conditionExit, thenExit)) }
        }
    }

    override fun visitWhileExpr(whileExpr: RsWhileExpr) {
        val loopBack = addDummyNode(listOf(pred))
        val exprExit = addAstNode(whileExpr, emptyList())
        val loopScope = LoopScope(whileExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val conditionExit = process(whileExpr.condition?.expr, loopBack)
            addContainedEdge(conditionExit, exprExit)

            val bodyExit = process(whileExpr.block, conditionExit)
            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(exprExit)
    }

    override fun visitLoopExpr(loopExpr: RsLoopExpr) {
        val loopBack = addDummyNode(listOf(pred))
        val exprExit = addAstNode(loopExpr, emptyList())
        val loopScope = LoopScope(loopExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val bodyExit = process(loopExpr.block, loopBack)
            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(exprExit)
    }

    // todo: not sure it is right because rustc uses HIR (where ForExpr replaced by LoopExpr) instead of AST
    override fun visitForExpr(forExpr: RsForExpr) {
        val loopBack = addDummyNode(listOf(pred))
        val exprExit = addAstNode(forExpr, emptyList())
        val loopScope = LoopScope(forExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val bodyExit = process(forExpr.block, loopBack)
            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(exprExit)
    }

    override fun visitBinaryExpr(binaryExpr: RsBinaryExpr) {
        if (binaryExpr.binaryOp.isLazy) {
            val leftExit = process(binaryExpr.left, pred)
            val rightExit = process(binaryExpr.right, leftExit)
            finishWith { addAstNode(binaryExpr, listOf(leftExit, rightExit)) }
        } else {
            finishWith { straightLine(binaryExpr, pred, listOf(binaryExpr.left, binaryExpr.right)) }
        }
        // todo: method calls
    }

    override fun visitRetExpr(retExpr: RsRetExpr) {
        val valueExit = process(retExpr.expr, pred)
        val returnExit = addAstNode(retExpr, listOf(valueExit))
        addReturningEdge(returnExit)
        finishWith { addUnreachableNode() }
    }

    // todo
    override fun visitBreakExpr(breakExpr: RsBreakExpr) = finishWith(pred)
    override fun visitContExpr(contExpr: RsContExpr) = finishWith(pred)

    override fun visitArrayExpr(arrayExpr: RsArrayExpr) =
        finishWith { straightLine(arrayExpr, pred, arrayExpr.exprList) }

    override fun visitCallExpr(callExpr: RsCallExpr) =
        finishWith { processCall(callExpr, pred, callExpr.expr, callExpr.valueArgumentList.exprList) }

    override fun visitIndexExpr(indexExpr: RsIndexExpr) =
        finishWith { processCall(indexExpr, pred, indexExpr.exprList.first(), indexExpr.exprList.drop(1)) }

    override fun visitUnaryExpr(unaryExpr: RsUnaryExpr) =
        finishWith { processCall(unaryExpr, pred, unaryExpr.expr, emptyList()) }

    override fun visitTupleExpr(tupleExpr: RsTupleExpr) =
        finishWith { straightLine(tupleExpr, pred, tupleExpr.exprList) }

    override fun visitCastExpr(castExpr: RsCastExpr) =
        finishWith { straightLine(castExpr, pred, listOf(castExpr.expr)) }

    override fun visitDotExpr(dotExpr: RsDotExpr) =
        finishWith { straightLine(dotExpr, pred, listOf(dotExpr.expr)) }

    override fun visitLitExpr(litExpr: RsLitExpr) =
        finishWith { straightLine(litExpr, pred, emptyList()) }

    override fun visitMatchExpr(matchExpr: RsMatchExpr) {
        fun processGuard(guard: RsMatchArmGuard, prevGuards: ArrayDeque<NodeIndex>, guardStart: NodeIndex): NodeIndex {
            val guardExit = process(guard, guardStart)

            prevGuards.forEach { addContainedEdge(it, guardStart) }
            prevGuards.clear()
            prevGuards.push(guardExit)

            return guardExit
        }

        val discriminantExit = process(matchExpr.expr, pred)
        val exprExit = addAstNode(matchExpr, emptyList())

        val prevGuards = ArrayDeque<NodeIndex>()

        matchExpr.matchBody?.matchArmList?.forEach { arm ->
            val armExit = addDummyNode(emptyList())

            arm.patList.forEach { pat ->
                var patExit = process(pat, discriminantExit)
                val guard = arm.matchArmGuard
                if (guard != null) {
                    val guardStart = addDummyNode(listOf(patExit))
                    patExit = processGuard(guard, prevGuards, guardStart)
                }
                addContainedEdge(patExit, armExit)
            }

            val bodyExit = process(arm.expr, armExit)
            addContainedEdge(bodyExit, exprExit)
        }

        finishWith(exprExit)
    }

    override fun visitParenExpr(parenExpr: RsParenExpr) = parenExpr.expr.accept(this)

    // todo
    override fun visitTryExpr(tryExpr: RsTryExpr) {}

    override fun visitElement(element: RsElement) = finishWith(pred)
}
