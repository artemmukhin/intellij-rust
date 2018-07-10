/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.ancestors
import org.rust.lang.core.psi.ext.valueParameters
import org.rust.lang.core.types.ty.TyNever
import org.rust.lang.core.types.type

sealed class CFGNodeData(val element: RsElement? = null) {
    class AST(element: RsElement) : CFGNodeData(element)
    object Entry : CFGNodeData()
    object Exit : CFGNodeData()
    object Dummy : CFGNodeData()
    object Unreachable : CFGNodeData()

    val text: String
        get() = when (this) {
            is AST -> element?.cfgText()?.trim() ?: "AST"
            is Entry -> "Entry"
            is Exit -> "Exit"
            is Dummy -> "Dummy"
            is Unreachable -> "Unreachable"
        }

    private fun RsElement.cfgText(): String =
        when (this) {
            is RsBlock, is RsBlockExpr -> "BLOCK"
            is RsIfExpr -> "IF"
            is RsWhileExpr -> "WHILE"
            is RsLoopExpr -> "LOOP"
            is RsForExpr -> "FOR"
            is RsMatchExpr -> "MATCH"
            is RsExprStmt -> expr.cfgText() + ";"
            else -> this.text
        }
}

class CFGEdgeData(val exitingScopes: MutableList<RsElement>)

typealias CFGGraph = Graph<CFGNodeData, CFGEdgeData>
typealias CFGNode = Node<CFGNodeData, CFGEdgeData>
typealias CFGEdge = Edge<CFGNodeData, CFGEdgeData>

class ControlFlowGraph(body: RsBlock) {
    val graph: CFGGraph = Graph()
    val owner: RsElement
    val entry: CFGNode
    val exit: CFGNode

    private val fnExit: CFGNode
    private val body: RsBlock

    init {
        this.entry = graph.addNode(CFGNodeData.Entry)
        this.owner = body.parent as RsElement
        this.fnExit = graph.addNode(CFGNodeData.Exit)

        val cfgBuilder = CFGBuilder(graph, entry, fnExit)
        val bodyExit = cfgBuilder.process(body, entry)
        cfgBuilder.addContainedEdge(bodyExit, fnExit)

        this.exit = fnExit
        this.body = body
    }

    fun isNodeReachable(item: RsElement) = graph.depthFirstTraversal(entry).any { it.data.element == item }

    fun buildLocalIndex(): HashMap<RsElement, MutableList<CFGNode>> {
        val table = hashMapOf<RsElement, MutableList<CFGNode>>()
        val func = body.parent

        if (func is RsFunction) {
            val formals = object : RsVisitor() {
                override fun visitPat(pat: RsPat) {
                    table.getOrPut(pat, ::mutableListOf).add(entry)
                    pat.acceptChildren(this)
                }
            }

            func.valueParameters.mapNotNull { it.pat }.forEach { formals.visitPat(it) }
        }

        graph.forEachNode { node ->
            val element = node.data.element
            if (element != null)
                table.getOrPut(element, ::mutableListOf).add(node)
        }

        return table
    }

    fun depthFirstTraversalTrace(): String =
        graph.depthFirstTraversal(this.entry).map { it.data.text }.joinToString("\n")


    /**
     * Creates graph description written in the DOT language.
     * Usage: copy the output into `cfg.dot` file and run `dot -Tpng cfg.dot -o cfg.png`
     */
    fun createDotDescription(): String {
        val sb = StringBuilder()
        sb.append("digraph {\n")
        graph.forEachEdge { edge ->
            val source = edge.source
            val target = edge.target
            val sourceNode = source.data
            val targetNode = target.data

            sb.append("    \"${source.index}: ${sourceNode.text}\" -> \"${target.index}: ${targetNode.text}\";\n")
        }
        sb.append("}\n")
        return sb.toString()
    }
}


sealed class ExitPoint {
    class Return(val e: RsRetExpr) : ExitPoint()
    class TryExpr(val e: RsExpr) : ExitPoint() // `?` or `try!`
    class DivergingExpr(val e: RsExpr) : ExitPoint()
    class TailExpr(val e: RsExpr) : ExitPoint()
    class TailStatement(val stmt: RsExprStmt) : ExitPoint()

    companion object {
        fun process(fn: RsFunction, sink: (ExitPoint) -> Unit) = fn.block?.acceptChildren(ExitPointVisitor(sink))
        fun process(fn: RsLambdaExpr, sink: (ExitPoint) -> Unit) = fn.expr?.acceptChildren(ExitPointVisitor(sink))
    }
}

private class ExitPointVisitor(
    private val sink: (ExitPoint) -> Unit
) : RsVisitor() {
    override fun visitElement(element: RsElement) = element.acceptChildren(this)

    override fun visitLambdaExpr(lambdaExpr: RsLambdaExpr) = Unit
    override fun visitFunction(function: RsFunction) = Unit

    override fun visitRetExpr(retExpr: RsRetExpr) = sink(ExitPoint.Return(retExpr))

    override fun visitTryExpr(tryExpr: RsTryExpr) {
        tryExpr.expr.acceptChildren(this)
        sink(ExitPoint.TryExpr(tryExpr))
    }

    override fun visitMacroExpr(macroExpr: RsMacroExpr) {
        if (macroExpr.macroCall.tryMacroArgument != null) sink(ExitPoint.TryExpr(macroExpr))
        if (macroExpr.type == TyNever) sink(ExitPoint.DivergingExpr(macroExpr))
    }

    override fun visitExpr(expr: RsExpr) {
        when (expr) {
            is RsIfExpr,
            is RsBlockExpr,
            is RsMatchExpr -> expr.acceptChildren(this)
            else -> {
                if (expr.isInTailPosition) sink(ExitPoint.TailExpr(expr)) else expr.acceptChildren(this)
            }
        }
    }

    override fun visitExprStmt(exprStmt: RsExprStmt) {
        exprStmt.acceptChildren(this)
        val block = exprStmt.parent as? RsBlock ?: return
        if (!(block.expr == null && block.stmtList.lastOrNull() == exprStmt)) return
        val parent = block.parent
        if ((parent is RsFunction || parent is RsExpr && parent.isInTailPosition) && exprStmt.expr.type != TyNever) {
            sink(ExitPoint.TailStatement(exprStmt))
        }
    }

    private val RsExpr.isInTailPosition: Boolean
        get() {
            for (ancestor in ancestors) {
                when (ancestor) {
                    is RsFunction, is RsLambdaExpr -> return true
                    is RsStmt, is RsCondition, is RsMatchArmGuard, is RsPat -> return false
                    else -> if (ancestor is RsExpr && ancestor.parent is RsMatchExpr) return false
                }
            }
            return false
        }
}
