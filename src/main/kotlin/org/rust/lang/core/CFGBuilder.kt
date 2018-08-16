/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.isLazy
import java.util.*

class CFGBuilder(val graph: Graph<CFGNodeData, CFGEdgeData>, val entry: CFGNode, val exit: CFGNode) : RsVisitor() {
    class BlockScope(val block: RsBlock, val breakNode: CFGNode)

    class LoopScope(val loop: RsExpr, val continueNode: CFGNode, val breakNode: CFGNode)

    enum class ScopeControlFlowKind { BREAK, CONTINUE }

    data class Destination(val label: RsLabel?, val target: RsElement)


    private var result: CFGNode? = null
    private val preds: Deque<CFGNode> = ArrayDeque<CFGNode>()
    private val pred: CFGNode get() = preds.peek()
    private val loopScopes: Deque<LoopScope> = ArrayDeque<LoopScope>()
    private val breakableBlockScopes: Deque<BlockScope> = ArrayDeque<BlockScope>()

    private inline fun finishWith(callable: () -> CFGNode) {
        result = callable()
    }

    private fun finishWith(value: CFGNode) {
        result = value
    }

    private fun finishWithAstNode(element: RsElement, pred: CFGNode) =
        finishWith { addAstNode(element, listOf(pred)) }

    private fun withLoopScope(loopScope: LoopScope, callable: () -> Unit) {
        loopScopes.push(loopScope)
        callable()
        loopScopes.pop()
    }

    private fun addAstNode(element: RsElement, preds: List<CFGNode>): CFGNode =
        addNode(CFGNodeData.AST(element), preds)

    private fun addDummyNode(preds: List<CFGNode>): CFGNode =
        addNode(CFGNodeData.Dummy, preds)

    private fun addUnreachableNode(): CFGNode =
        addNode(CFGNodeData.Unreachable, emptyList())

    private fun addNode(data: CFGNodeData, preds: List<CFGNode>): CFGNode {
        val newNode = graph.addNode(data)
        preds.forEach { addContainedEdge(it, newNode) }
        return newNode
    }

    fun addContainedEdge(source: CFGNode, target: CFGNode) {
        val data = CFGEdgeData(mutableListOf())
        graph.addEdge(source, target, data)
    }

    private fun addReturningEdge(fromNode: CFGNode) {
        val data = CFGEdgeData(loopScopes.map { it.loop }.toMutableList())
        graph.addEdge(fromNode, exit, data)
    }

    private fun straightLine(expr: RsExpr, pred: CFGNode, subExprs: List<RsExpr?>): CFGNode {
        val subExprsExit = subExprs.fold(pred) { acc, subExpr -> process(subExpr, acc) }
        return addAstNode(expr, listOf(subExprsExit))
    }

    fun process(element: RsElement?, pred: CFGNode): CFGNode {
        if (element == null) return pred

        val oldPredsSize = preds.size
        preds.push(pred)
        element.accept(this)
        preds.pop()
        assert(preds.size == oldPredsSize)

        return checkNotNull(result) { "Processing ended inconclusively" }
    }

    private fun allPats(pat: RsPat, subPats: List<RsPat?>): CFGNode {
        val patsExit = subPats.fold(pred) { pred, subPat -> process(subPat, pred) }
        return addAstNode(pat, listOf(patsExit))
    }

    private fun processCall(callExpr: RsExpr, func: RsExpr?, args: List<RsExpr?>): CFGNode {
        val funcExit = process(func, pred)
        return straightLine(callExpr, funcExit, args)
    }

    override fun visitBlock(block: RsBlock) {
        val stmtsExit = block.stmtList.fold(pred) { pred, stmt -> process(stmt, pred) }
        val blockExpr = block.expr
        val exprExit = process(blockExpr, stmtsExit)

        finishWithAstNode(block, exprExit)
    }

    override fun visitLetDecl(letDecl: RsLetDecl) {
        val initExit = process(letDecl.expr, pred)
        val exit = process(letDecl.pat, initExit)

        finishWithAstNode(letDecl, exit)
    }

    override fun visitFieldDecl(fieldDecl: RsFieldDecl) = finishWith(pred)

    override fun visitLabelDecl(labelDecl: RsLabelDecl) = finishWith(pred)

    override fun visitExprStmt(exprStmt: RsExprStmt) {
        val exit = process(exprStmt.expr, pred)
        finishWithAstNode(exprStmt, exit)
    }

    override fun visitPatBinding(patBinding: RsPatBinding) =
        finishWithAstNode(patBinding, pred)

    override fun visitPatIdent(patIdent: RsPatIdent) =
        finishWithAstNode(patIdent, pred)

    override fun visitPatRange(patRange: RsPatRange) =
        finishWithAstNode(patRange, pred)

    override fun visitPatConst(patConst: RsPatConst) =
        finishWithAstNode(patConst, pred)

    override fun visitPatWild(patWild: RsPatWild) =
        finishWithAstNode(patWild, pred)

    override fun visitPathExpr(pathExpr: RsPathExpr) =
        finishWithAstNode(pathExpr, pred)

    override fun visitRangeExpr(rangeExpr: RsRangeExpr) =
        finishWithAstNode(rangeExpr, pred)

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
        finishWithAstNode(blockExpr, blockExit)
    }

    override fun visitIfExpr(ifExpr: RsIfExpr) {
        //
        //      [pred]             [pred]
        //        |                  |
        //        v 1                v 1
        //      [cond]             [cond]
        //        |                  |
        //       / \                / \
        //      /   \              /   \
        //     v 2   v 3          v 2   *
        //   [then][else]       [then]  |
        //     |     |            |     |
        //     v 4   v 5          v 3   v 4
        //     [ifExpr]          [ifExpr]
        //
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
        //
        //         [pred]
        //           |
        //           v 1
        //       [loopback] <--+ 5
        //           |         |
        //           v 2       |
        //   +-----[cond]      |
        //   |       |         |
        //   |       v 4       |
        //   |     [body] -----+
        //   v 3
        // [whileExpr]
        //
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
        //
        //     [pred]
        //       |
        //       v 1
        //   [loopback] <---+
        //       |      4   |
        //       v 3        |
        //     [body] ------+
        //
        //   [loopExpr] 2
        //
        val loopBack = addDummyNode(listOf(pred))
        val exprExit = addAstNode(loopExpr, emptyList())
        val loopScope = LoopScope(loopExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val bodyExit = process(loopExpr.block, loopBack)
            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(exprExit)
    }

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
        // TODO: method calls
    }

    override fun visitRetExpr(retExpr: RsRetExpr) {
        val valueExit = process(retExpr.expr, pred)
        val returnExit = addAstNode(retExpr, listOf(valueExit))
        addReturningEdge(returnExit)
        finishWith { addUnreachableNode() }
    }

    // TODO: this cases require regions which are not implemented yet
    override fun visitBreakExpr(breakExpr: RsBreakExpr) = finishWith(pred)
    override fun visitContExpr(contExpr: RsContExpr) = finishWith(pred)

    override fun visitArrayExpr(arrayExpr: RsArrayExpr) =
        finishWith { straightLine(arrayExpr, pred, arrayExpr.exprList) }

    override fun visitCallExpr(callExpr: RsCallExpr) =
        finishWith { processCall(callExpr, callExpr.expr, callExpr.valueArgumentList.exprList) }

    override fun visitIndexExpr(indexExpr: RsIndexExpr) =
        finishWith { processCall(indexExpr, indexExpr.exprList.first(), indexExpr.exprList.drop(1)) }

    override fun visitUnaryExpr(unaryExpr: RsUnaryExpr) =
        finishWith { processCall(unaryExpr, unaryExpr.expr, emptyList()) }

    override fun visitTupleExpr(tupleExpr: RsTupleExpr) =
        finishWith { straightLine(tupleExpr, pred, tupleExpr.exprList) }

    override fun visitCastExpr(castExpr: RsCastExpr) =
        finishWith { straightLine(castExpr, pred, listOf(castExpr.expr)) }

    override fun visitDotExpr(dotExpr: RsDotExpr) =
        finishWith { straightLine(dotExpr, pred, listOf(dotExpr.expr)) }

    override fun visitLitExpr(litExpr: RsLitExpr) =
        finishWith { straightLine(litExpr, pred, emptyList()) }

    override fun visitMatchExpr(matchExpr: RsMatchExpr) {
        fun processGuard(guard: RsMatchArmGuard, prevGuards: ArrayDeque<CFGNode>, guardStart: CFGNode): CFGNode {
            val guardExit = process(guard, guardStart)

            prevGuards.forEach { addContainedEdge(it, guardStart) }
            prevGuards.clear()
            prevGuards.push(guardExit)

            return guardExit
        }

        val discriminantExit = process(matchExpr.expr, pred)
        val exprExit = addAstNode(matchExpr, emptyList())

        val prevGuards = ArrayDeque<CFGNode>()

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

    override fun visitTryExpr(tryExpr: RsTryExpr) {
        val exprExit = addAstNode(tryExpr, emptyList())
        val checkExpr = addDummyNode(listOf(pred))
        val expr = addAstNode(tryExpr.expr, listOf(checkExpr))
        addReturningEdge(checkExpr)
        addContainedEdge(expr, exprExit)
        finishWith(exprExit)
    }

    override fun visitElement(element: RsElement) = finishWith(pred)
}
