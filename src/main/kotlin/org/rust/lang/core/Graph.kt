/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import java.util.*

class Graph<N, E>(val nodes: MutableList<Node<N>>, val edges: MutableList<Edge<E>>) {
    constructor() : this(mutableListOf(), mutableListOf())

    private fun nextNodeIndex(): NodeIndex = NodeIndex(nodes.size)
    private fun nextEdgeIndex(): EdgeIndex = EdgeIndex(edges.size)

    fun addNode(data: N): NodeIndex {
        val idx = nextNodeIndex()

        val newNode = Node(INVALID_EDGE_INDEX, INVALID_EDGE_INDEX, data)
        nodes.add(newNode)

        return idx
    }

    fun addEdge(source: NodeIndex, target: NodeIndex, data: E): EdgeIndex {
        val idx = nextEdgeIndex()

        val sourceFirst = nodes[source.index].firstOutEdge
        val targetFirst = nodes[target.index].firstInEdge

        val newEdge = Edge(sourceFirst, targetFirst, source, target, data)
        edges.add(newEdge)

        nodes[source.index].firstOutEdge = idx
        nodes[target.index].firstInEdge = idx

        return idx
    }

    fun nodeData(idx: NodeIndex) = nodes[idx.index].data

    val nodeIndices get(): List<NodeIndex> = nodes.mapIndexed { index, _ -> NodeIndex(index) }

    fun outgoingEdges(source: NodeIndex): Sequence<EdgeIndex> =
        generateSequence(nodes[source.index].firstOutEdge) {
            edges[it.index].nextSourceEdge
        }

    fun incomingEdges(target: NodeIndex): Sequence<EdgeIndex> =
        generateSequence(nodes[target.index].firstInEdge) {
            edges[it.index].nextTargetEdge
        }

    fun forEachNode(f: (NodeIndex, Node<N>) -> Unit) = nodes.forEachIndexed { index, node -> f(NodeIndex(index), node) }

    fun depthFirstTraversal(startNode: NodeIndex): Sequence<NodeIndex> {
        val visited = mutableSetOf(startNode)
        val stack = ArrayDeque<NodeIndex>()
        stack.push(startNode)

        val visit = { node: NodeIndex -> if (visited.add(node)) stack.push(node) }

        return generateSequence {
            val next = stack.poll()
            if (next != null) {
                outgoingEdges(next).forEach { edge ->
                    val target = edges[edge.index].target
                    visit(target)
                }
            }
            next
        }
    }

    // todo
    /*
    fun nodesInPostorder(entryNode: NodeIndex): List<NodeIndex> {
        val visited = mutableSetOf<NodeIndex>()
        val stack = ArrayDeque<Pair<NodeIndex, Sequence<EdgeIndex>>>()
        val result = mutableListOf<NodeIndex>()
        val pushNode = { node: NodeIndex ->
            if (visited.add(node)) stack.push(Pair(node, outgoingEdges(node)))
        }

        val nodesWithEntry = listOf(entryNode) + nodeIndices
        for (node in nodesWithEntry) {
            var stackHead = stack.poll()
            while (stackHead != null) {
                val (node, iter) = stackHead

            }
        }
        nodesWithEntry.forEach { node ->
            pushNode(node)
            val (node, iter) = stack.poll()
            for ((node, iter) in stack) {
            }
            while ()
        }
    }
    */
}

class Node<N>(var firstOutEdge: EdgeIndex,
              var firstInEdge: EdgeIndex,
              val data: N)

private val INVALID_EDGE_INDEX: EdgeIndex = EdgeIndex(Int.MAX_VALUE)

class Edge<E>(val nextSourceEdge: EdgeIndex,
              val nextTargetEdge: EdgeIndex,
              val source: NodeIndex,
              val target: NodeIndex,
              val data: E)

class NodeIndex(val index: Int)
class EdgeIndex(val index: Int)

