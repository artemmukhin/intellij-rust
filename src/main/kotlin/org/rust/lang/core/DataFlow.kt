/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.cfg.CFGEdge
import org.rust.lang.core.cfg.CFGNode
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement
import java.util.*

enum class EntryOrExit { Entry, Exit }

class DataFlowContext<O : DataFlowOperator>(val analysisName: String,
                                            val body: RsBlock,
                                            val cfg: ControlFlowGraph,
                                            val oper: O,
                                            val bitsPerElement: Int) {
    val bitsPerInt: Int = 32
    val wordsPerElement: Int
    val gens: MutableList<Int>          //
    val scopeKills: MutableList<Int>    // todo: maybe it's better to use BitSet
    val actionKills: MutableList<Int>   //
    val onEntry: MutableList<Int>
    val localIndexTable: HashMap<RsElement, MutableList<CFGNode>>

    init {
        val nodesCount = cfg.graph.size
        val entry = oper.neutralElement

        this.wordsPerElement = (bitsPerElement + bitsPerInt - 1) / bitsPerInt
        this.gens = MutableList(nodesCount * wordsPerElement) { 0 }
        this.actionKills = MutableList(nodesCount * wordsPerElement) { 0 }
        this.scopeKills = MutableList(nodesCount * wordsPerElement) { 0 }
        this.onEntry = MutableList(nodesCount * wordsPerElement) { entry }
        this.localIndexTable = cfg.buildLocalIndex()
    }

    private fun getCfgNodes(element: RsElement) = localIndexTable.getOrDefault(element, mutableListOf())

    private fun hasBitSetForLocalElement(element: RsElement): Boolean = localIndexTable.containsKey(element)

    fun computeIdRange(node: CFGNode): Pair<Int, Int> {
        val start = node.index * wordsPerElement
        val end = start + wordsPerElement
        return Pair(start, end)
    }

    private fun setBit(words: MutableList<Int>, bit: Int): Boolean {
        val word = bit / bitsPerInt
        val bitInWord = bit % bitsPerInt
        val bitMask = 1 shl bitInWord
        val oldValue = words[word]
        val newValue = oldValue or bitMask
        words[word] = newValue
        return (oldValue != newValue)
    }

    fun addGen(element: RsElement, bit: Int) {
        getCfgNodes(element).forEach {
            val (start, end) = computeIdRange(it)
            setBit(gens.subList(start, end), bit)
        }
    }

    fun addKill(kind: KillFrom, element: RsElement, bit: Int) {
        getCfgNodes(element).forEach {
            val (start, end) = computeIdRange(it)
            when (kind) {
                KillFrom.ScopeEnd -> setBit(scopeKills.subList(start, end), bit)
                KillFrom.Execution -> setBit(actionKills.subList(start, end), bit)
            }
        }
    }

    fun applyGenKill(node: CFGNode, bits: List<Int>): MutableList<Int> {
        val (start, end) = computeIdRange(node)
        val result = bits.toMutableList()
        Union.bitwise(result, gens.subList(start, end))
        Subtract.bitwise(result, actionKills.subList(start, end))
        Subtract.bitwise(result, scopeKills.subList(start, end))
        return result
    }

    fun eachBitOnEntry(element: RsElement, f: (Int) -> Boolean): Boolean {
        if (!hasBitSetForLocalElement(element)) return true
        val nodes = getCfgNodes(element)
        return nodes.all { eachBitForNode(EntryOrExit.Entry, it, f) }
    }

    fun eachBitForNode(e: EntryOrExit, node: CFGNode, f: (Int) -> Boolean): Boolean {
        if (bitsPerElement == 0) return true

        val (start, end) = computeIdRange(node)
        val onEntry = onEntry.subList(start, end)
        val slice = when (e) {
            EntryOrExit.Entry -> onEntry
            EntryOrExit.Exit -> applyGenKill(node, onEntry)
        }
        return eachBit(slice, f)
    }

    fun eachGenBit(element: RsElement, f: (Int) -> Boolean): Boolean {
        if (!hasBitSetForLocalElement(element)) return true
        if (bitsPerElement == 0) return true

        val nodes = getCfgNodes(element)
        return nodes.all {
            val (start, end) = computeIdRange(it)
            eachBit(gens.subList(start, end), f)
        }
    }

    fun eachBit(words: List<Int>, f: (Int) -> Boolean): Boolean {
        words.filter { it != 0 }.forEachIndexed { index, word ->
            val baseIndex = index * bitsPerInt
            for (offset in 0..bitsPerInt) {
                val bit = 1 shl offset
                if (word and bit != 0) {
                    val bitIndex = baseIndex + offset
                    if (bitIndex >= bitsPerElement) {
                        return true
                    } else if (!f(bitIndex)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    // TODO: requires regions which are not implemented yet
    fun addKillsFromFlowExits() {}

    fun propagate() {
        if (bitsPerElement == 0) return

        val propagationContext = PropagationContext(this, true)
        val nodesInPostOrder = cfg.graph.nodesInPostOrder(cfg.entry)
        var numberOfPasses = 0
        while (propagationContext.changed) {
            numberOfPasses++
            propagationContext.changed = false
            propagationContext.walkCfg(nodesInPostOrder)
        }
    }
}

interface BitwiseOperator {
    fun join(succ: Int, pred: Int): Int

    fun bitwise(outBits: MutableList<Int>, inBits: List<Int>): Boolean {
        var changed = false

        outBits.zip(inBits).forEachIndexed { i, (outBit, inBit) ->
            val newValue = join(outBit, inBit)
            outBits[i] = newValue
            changed = changed or (outBit != newValue)
        }

        return changed
    }
}

object Union : BitwiseOperator {
    override fun join(succ: Int, pred: Int) = succ or pred
}

object Subtract : BitwiseOperator {
    override fun join(succ: Int, pred: Int) = succ and pred.inv()
}

interface DataFlowOperator : BitwiseOperator {
    val initialValue: Boolean
    val neutralElement: Int get() = if (initialValue) Int.MAX_VALUE else 0
}

class PropagationContext<O : DataFlowOperator>(val dataFlowContext: DataFlowContext<O>, var changed: Boolean) {
    val graph = dataFlowContext.cfg.graph

    fun walkCfg(nodesInPostOrder: List<CFGNode>) {
        // walking in reverse post-order
        nodesInPostOrder.asReversed().forEach { node ->
            val (start, end) = dataFlowContext.computeIdRange(node)
            val onEntry = dataFlowContext.onEntry.subList(start, end).toList()
            val result = dataFlowContext.applyGenKill(node, onEntry)
            propagateBitsIntoGraphSuccessorsOf(result, node)
        }
    }

    private fun propagateBitsIntoGraphSuccessorsOf(predBits: List<Int>, node: CFGNode) =
        graph.outgoingEdges(node).forEach {
            propagateBitsIntoEntrySetFor(predBits, graph.getEdge(it.index))
        }

    private fun propagateBitsIntoEntrySetFor(predBits: List<Int>, edge: CFGEdge) {
        val target = edge.target
        val (start, end) = dataFlowContext.computeIdRange(target)
        val onEntry = dataFlowContext.onEntry.subList(start, end)
        val changed = dataFlowContext.oper.bitwise(onEntry, predBits)
        if (changed) this.changed = true
    }
}

enum class KillFrom {
    ScopeEnd, // e.g. a kill associated with the end of the scope of a variable declaration `let x;`
    Execution // e.g. a kill associated with an assignment statement `x = expr;`
}
