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

sealed class CFGNode(val element: RsElement? = null) {
    class AST(element: RsElement) : CFGNode(element)
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

    private val fnExit: NodeIndex
    private val body: RsBlock

    init {
        this.entry = graph.addNode(CFGNode.Entry)
        this.owner = body.parent as RsElement
        this.fnExit = graph.addNode(CFGNode.Exit)

        val cfgBuilder = CFGBuilder(graph, entry, fnExit)
        val bodyExit = cfgBuilder.process(body, entry)
        cfgBuilder.addContainedEdge(bodyExit, fnExit)

        this.exit = fnExit
        this.body = body
    }

    fun isNodeReachable(item: RsElement) = graph.depthFirstTraversal(entry).any { graph.nodeData(it).element == item }

    fun findUnreachableStatements() = body.stmtList.filter { !isNodeReachable(it) }

    fun buildLocalIndex(): HashMap<RsElement, MutableList<NodeIndex>> {
        val table = hashMapOf<RsElement, MutableList<NodeIndex>>()
        val func = body.parent

        if (func is RsFunction) {
            val formals = object : RsVisitor() {
                override fun visitPat(pat: RsPat) {
                    table.getOrPut(pat, { mutableListOf() }).add(entry)
                    pat.acceptChildren(this)
                }
            }

            func.valueParameters.map { it -> it.pat }.forEach { pat ->
                if (pat != null) formals.visitPat(pat)
            }
        }

        graph.forEachNode { i, node ->
            val element = node.data.element
            if (element != null)
                table.getOrPut(element, { mutableListOf() }).add(i)
        }

        return table
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

    override fun visitLambdaExpr(o: RsLambdaExpr) = Unit
    override fun visitFunction(o: RsFunction) = Unit

    override fun visitRetExpr(o: RsRetExpr) = sink(ExitPoint.Return(o))

    override fun visitTryExpr(o: RsTryExpr) {
        o.expr.acceptChildren(this)
        sink(ExitPoint.TryExpr(o))
    }

    override fun visitMacroExpr(o: RsMacroExpr) {
        if (o.macroCall.tryMacroArgument != null) sink(ExitPoint.TryExpr(o))
        if (o.type == TyNever) sink(ExitPoint.DivergingExpr(o))
    }

    override fun visitExpr(o: RsExpr) {
        when (o) {
            is RsIfExpr,
            is RsBlockExpr,
            is RsMatchExpr -> o.acceptChildren(this)
            else -> {
                if (o.isInTailPosition) sink(ExitPoint.TailExpr(o)) else o.acceptChildren(this)
            }
        }
    }

    override fun visitExprStmt(o: RsExprStmt) {
        o.acceptChildren(this)
        val block = o.parent as? RsBlock ?: return
        if (!(block.expr == null && block.stmtList.lastOrNull() == o)) return
        val parent = block.parent
        if ((parent is RsFunction || parent is RsExpr && parent.isInTailPosition) && o.expr.type != TyNever) {
            sink(ExitPoint.TailStatement(o))
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
