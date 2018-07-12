/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement

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

    fun findUnreachableStmts() = body.stmtList.filter { !isNodeReachable(it) }
}
