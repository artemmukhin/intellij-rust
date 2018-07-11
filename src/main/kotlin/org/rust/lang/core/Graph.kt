/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

class Graph<N, E>(private val nodes: MutableList<Node<N>>, private val edges: MutableList<Edge<E>>) {
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

    fun outgoingEdges(source: NodeIndex) = nodes[source.index].firstOutEdge

    fun incomingEdges(target: NodeIndex) = nodes[target.index].firstInEdge

    fun depthFirstTraversal(startNode: NodeIndex): Sequence<NodeIndex> =
        generateSequence(startNode) {
            val idx = outgoingEdges(it)
            if (idx != INVALID_EDGE_INDEX) edges[idx.index].target else null
        }
}

class Node<N>(var firstOutEdge: EdgeIndex,
              var firstInEdge: EdgeIndex,
              val data: N) {
}

private val INVALID_EDGE_INDEX: EdgeIndex = EdgeIndex(Int.MAX_VALUE)

class Edge<E>(val nextSourceEdge: EdgeIndex,
              val nextTargetEdge: EdgeIndex,
              val source: NodeIndex,
              val target: NodeIndex,
              val data: E) {
}

class NodeIndex(val index: Int)
class EdgeIndex(val index: Int)

