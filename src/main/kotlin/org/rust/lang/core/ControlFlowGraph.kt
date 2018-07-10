/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement

sealed class CFGNode {
    data class AST(val element: RsElement) : CFGNode()
    object Entry : CFGNode()
    object Exit : CFGNode()
    object Dummy : CFGNode()
    object Unreachable : CFGNode()
}

class CFGEdge(val exitingScopes: List<RsElement>) {
}

class CFG(body: RsBlock) {
    val graph: Graph<CFGNode, CFGEdge> = Graph()
    val owner: RsElement
    val entry: NodeIndex
    val exit: NodeIndex

    init {
        this.entry = graph.addNode(CFGNode.Entry)
        this.owner = body.parent as RsElement
        this.exit = graph.addNode(CFGNode.Exit)

        val bodyExit = process(body.expr, entry)
        addContainedEdge(bodyExit, this.exit)
    }

    fun nodeIsReachable(item: RsElement) {
        graph.depthFirstTraversal(entry).any { graph.nodeData(it) == item }
    }

    fun addAstNode(element: RsElement, preds: List<NodeIndex>): NodeIndex = addNode(CFGNode.AST(element), preds)

    fun addNode(node: CFGNode, preds: List<NodeIndex>): NodeIndex {
        val newNode = graph.addNode(node)
        preds.forEach { addContainedEdge(it, newNode) }
        return newNode
    }

    fun addContainedEdge(source: NodeIndex, target: NodeIndex) {
        val data = CFGEdge(emptyList())
        graph.addEdge(source, target, data)
    }

    fun process(element: RsElement?, pred: NodeIndex): NodeIndex =
        when (element) {
            is RsBlock -> processBlock(element, pred)
            is RsStmt -> processStmt(element, pred)
            is RsPat -> processPat(element, pred)
            is RsExpr -> processExpr(element, pred)
            else -> pred
        }

    fun processBlock(block: RsBlock, pred: NodeIndex): NodeIndex {
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
        // todo
        return pred
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
                }
                else {
                    addAstNode(expr, listOf(conditionExit, thenExit))
                }
            }

            // todo
            is RsWhileExpr -> {
                pred
            }
            // ...

            else -> pred
        }
    }

}

class BlockScope(val block: RsBlock, val breakIndex: NodeIndex)

class LoopScope(val loop: RsLoopExpr, val continueIndex: NodeIndex, val breakIndex: NodeIndex)
