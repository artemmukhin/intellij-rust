/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.isLazy
import java.util.*

sealed class CFGNode {
    data class AST(val element: RsElement) : CFGNode()
    object Entry : CFGNode()
    object Exit : CFGNode()
    object Dummy : CFGNode()
    object Unreachable : CFGNode()
}

class CFGEdge(val exitingScopes: MutableList<RsElement>)

class CFG(body: RsBlock) {
    val graph: Graph<CFGNode, CFGEdge> = Graph()
    val owner: RsElement
    val entry: NodeIndex
    val exit: NodeIndex

    private val loopScopes: Deque<LoopScope> = ArrayDeque<LoopScope>()
    private val breakableBlockScopes: Deque<BlockScope> = ArrayDeque<BlockScope>()
    private val fnExit: NodeIndex

    init {
        this.entry = graph.addNode(CFGNode.Entry)
        this.owner = body.parent as RsElement
        this.fnExit = graph.addNode(CFGNode.Exit)

        val bodyExit = process(body, entry)
        addContainedEdge(bodyExit, fnExit)

        this.exit = fnExit
    }

    fun nodeIsReachable(item: RsElement) {
        graph.depthFirstTraversal(entry).any { graph.nodeData(it) == item }
    }

    private fun addAstNode(element: RsElement, preds: List<NodeIndex>): NodeIndex = addNode(CFGNode.AST(element), preds)

    private fun addDummyNode(preds: List<NodeIndex>): NodeIndex = addNode(CFGNode.Dummy, preds)

    private fun addUnreachableNode(): NodeIndex = addNode(CFGNode.Unreachable, emptyList())

    private fun addNode(node: CFGNode, preds: List<NodeIndex>): NodeIndex {
        val newNode = graph.addNode(node)
        preds.forEach { addContainedEdge(it, newNode) }
        return newNode
    }

    private fun addContainedEdge(source: NodeIndex, target: NodeIndex) {
        val data = CFGEdge(mutableListOf())
        graph.addEdge(source, target, data)
    }

    private fun addReturningEdge(fromIndex: NodeIndex) {
        val edge = CFGEdge(loopScopes.map { it.loop }.toMutableList())
        graph.addEdge(fromIndex, fnExit, edge)
    }

    private fun straightLine(expr: RsExpr, pred: NodeIndex, subExprs: List<RsExpr?>): NodeIndex {
        val subExprsExit = subExprs.fold(pred) { pred, expr -> process(expr, pred)}
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

    private fun process(element: RsElement?, pred: NodeIndex): NodeIndex =
        when (element) {
            is RsBlock -> processBlock(element, pred)
            is RsStmt -> processStmt(element, pred)
            is RsPat -> processPat(element, pred)
            is RsExpr -> processExpr(element, pred)
            else -> pred
        }

    private fun processBlock(block: RsBlock, pred: NodeIndex): NodeIndex {
        // todo: targeted_by_break
        var stmtsExit = pred
        block.stmtList.forEach {
            stmtsExit = process(it, stmtsExit)
        }

        val blockExpr = block.expr
        val exprExit = process(blockExpr, stmtsExit)

        return addAstNode(block, listOf(exprExit))
    }

    private fun processStmt(stmt: RsStmt, pred: NodeIndex): NodeIndex {
        return when (stmt) {
            is RsLetDecl -> {
                val initExit = process(stmt.expr, pred)
                process(stmt.pat, initExit)
            }

            is RsFieldDecl, is RsLabelDecl -> pred

            is RsExprStmt -> {
                val exit = process(stmt.expr, pred)
                addAstNode(stmt, listOf(exit))
            }

            else -> pred
        }
    }

    private fun processPat(pat: RsPat, pred: NodeIndex): NodeIndex {

        fun allPats(pats: List<RsPat?>): NodeIndex {
            val patsExit = pats.fold(pred) { pred, pat -> process(pat, pred) }
            return addAstNode(pat, listOf(patsExit))
        }

        return when (pat) {
            is RsPatBinding, is RsPatRange, is RsPatConst, is RsPatWild -> addAstNode(pat, listOf(pred))

            is RsPatTup -> allPats(pat.patList)

            is RsPatTupleStruct -> allPats(pat.patList)

            is RsPatStruct -> allPats(pat.patFieldList.map { it.pat })

            // todo: add pre, vec, post
            is RsPatSlice -> allPats(pat.patList)

            else -> pred
        }
    }

    private fun processCall(callExpr: RsExpr, pred: NodeIndex, func: RsExpr?, args: List<RsExpr?>): NodeIndex {
        val funcExit = process(func, pred)
        return straightLine(callExpr, funcExit, args)
    }

    private fun processExpr(expr: RsExpr, pred: NodeIndex): NodeIndex {
        return when (expr) {
            is RsBlockExpr -> {
                val blockExit = process(expr.block, pred)
                addAstNode(expr, listOf(blockExit))
            }

            is RsIfExpr -> {
                val conditionExit = process(expr.condition?.expr, pred)
                val thenExit = process(expr.block, conditionExit)

                val elseBranch = expr.elseBranch
                if (elseBranch != null) {
                    val elseExit = process(elseBranch.block, conditionExit)
                    addAstNode(expr, listOf(thenExit, elseExit))
                } else {
                    addAstNode(expr, listOf(conditionExit, thenExit))
                }
            }

            is RsWhileExpr -> {
                val loopback = addDummyNode(listOf(pred))

                val exprExit = addAstNode(expr, emptyList())

                val loopScope = LoopScope(expr, loopback, exprExit)
                loopScopes.push(loopScope)

                val conditionExit = process(expr.condition?.expr, loopback)
                addContainedEdge(conditionExit, exprExit)

                val bodyExit = process(expr.block, conditionExit)
                addContainedEdge(bodyExit, loopback)

                loopScopes.pop()
                exprExit
            }

            is RsLoopExpr -> {
                val loopback = addDummyNode(listOf(pred))

                val exprExit = addAstNode(expr, emptyList())

                val loopScope = LoopScope(expr, loopback, exprExit)
                loopScopes.push(loopScope)

                val bodyExit = process(expr.block, loopback)
                addContainedEdge(bodyExit, loopback)

                loopScopes.pop()
                exprExit
            }

            is RsBinaryExpr -> {
                if (expr.binaryOp.isLazy) {
                    val leftExit = process(expr.left, pred)
                    val rightExit = process(expr.right, leftExit)
                    addAstNode(expr, listOf(leftExit, rightExit))
                }
                else {
                    straightLine(expr, pred, listOf(expr.left, expr.right))
                }
                // todo: method calls
            }

            is RsRetExpr -> {
                val valueExit = process(expr.expr, pred)
                val b = addAstNode(expr, listOf(valueExit))
                addReturningEdge(b)
                addUnreachableNode()
            }

            // todo: regions needed
            is RsBreakExpr -> pred
            is RsContExpr -> pred

            is RsArrayExpr -> straightLine(expr, pred, expr.exprList)

            is RsCallExpr -> processCall(expr, pred, expr.expr, expr.valueArgumentList.exprList)

            is RsIndexExpr -> processCall(expr, pred, expr.exprList.first(), expr.exprList.drop(1))

            is RsUnaryExpr -> processCall(expr, pred, expr.expr, emptyList())

            is RsTupleExpr -> straightLine(expr, pred, expr.exprList)

            is RsCastExpr -> straightLine(expr, pred, listOf(expr.expr))

            // ...

            else -> pred
        }
    }

}

class BlockScope(val block: RsBlock, val breakIndex: NodeIndex)

class LoopScope(val loop: RsExpr, val continueIndex: NodeIndex, val breakIndex: NodeIndex)

enum class ScopeControlFlowKind { BREAK, CONTINUE }

data class Destination(val label: RsLabel?, val target: RsElement)
