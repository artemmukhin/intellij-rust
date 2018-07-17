/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement
import java.util.*

enum class EntryOrExit { Entry, Exit }

class DataFlowContext<O : DataFlowOperator>(val analysisName: String,
                                            val body: RsBlock,
                                            val cfg: CFG,
                                            val oper: O,
                                            val bitsPerId: Int) {
    val bitsPerInt: Int = 32
    val wordsPerId: Int
    val gens: MutableList<Int>          //
    val scopeKills: MutableList<Int>    // todo: maybe it's better to use BitSet
    val actionKills: MutableList<Int>   //
    val onEntry: MutableList<Int>
    val localIndexTable: HashMap<RsElement, MutableList<NodeIndex>>

    init {
        val numNodes = cfg.graph.nodes.size
        val entry = if (oper.initialValue) Int.MAX_VALUE else 0

        this.wordsPerId = (bitsPerId + bitsPerInt - 1) / bitsPerInt
        this.gens = MutableList(numNodes * wordsPerId) { 0 }
        this.actionKills = MutableList(numNodes * wordsPerId) { 0 }
        this.scopeKills = MutableList(numNodes * wordsPerId) { 0 }
        this.onEntry = MutableList(numNodes * wordsPerId) { entry }
        this.localIndexTable = cfg.buildLocalIndex()
    }

    private fun getCfgIndices(element: RsElement) = localIndexTable.getOrDefault(element, mutableListOf())

    private fun hasBitsetForLocalElement(element: RsElement): Boolean = localIndexTable.containsKey(element)

    private fun computeIdRange(idx: NodeIndex): Pair<Int, Int> {
        val start = idx.index * wordsPerId
        val end = start + wordsPerId
        return Pair(start, end)
    }

    private fun setBit(words: MutableList<Int>, start: Int, bit: Int): Boolean {
        val word = bit / bitsPerInt
        val bitInWord = bit % bitsPerInt
        val bitMask = 1 shl bitInWord
        val oldValue = words[start + word]
        val newValue = oldValue or bitMask
        words[start + word] = newValue
        return (oldValue != newValue)
    }

    fun addGen(element: RsElement, bit: Int) {
        getCfgIndices(element).forEach { idx ->
            val (start, _) = computeIdRange(idx)
            setBit(gens, start, bit)
        }
    }

    fun addKill(kind: KillFrom, element: RsElement, bit: Int) {
        getCfgIndices(element).forEach { idx ->
            val (start, _) = computeIdRange(idx)
            when (kind) {
                KillFrom.ScopeEnd -> setBit(scopeKills, start, bit)
                KillFrom.Execution -> setBit(actionKills, start, bit)
            }
        }
    }

    fun applyGenKill(idx: NodeIndex, bits: List<Int>): MutableList<Int> {
        val (start, end) = computeIdRange(idx)
        val result = bits.toMutableList()
        Union.bitwise(result, gens.subList(start, end))
        Subtract.bitwise(result, actionKills.subList(start, end))
        Subtract.bitwise(result, scopeKills.subList(start, end))
        return result
    }

    fun eachBitOnEntry(element: RsElement, f: (Int) -> Boolean): Boolean {
        if (!hasBitsetForLocalElement(element)) return true
        val indices = getCfgIndices(element)
        return indices.all { eachBitForNode(EntryOrExit.Entry, it, f) }
    }

    fun eachBitForNode(e: EntryOrExit, idx: NodeIndex, f: (Int) -> Boolean): Boolean {
        if (bitsPerId == 0) return true

        val (start, end) = computeIdRange(idx)
        val onEntry = onEntry.subList(start, end)
        val slice = when (e) {
            EntryOrExit.Entry -> onEntry
            EntryOrExit.Exit -> applyGenKill(idx, onEntry)
        }
        return eachBit(slice, f)
    }

    fun eachBit(words: List<Int>, f: (Int) -> Boolean): Boolean {
        words.filter { it != 0 }.forEachIndexed { index, word ->
            val baseIndex = index * bitsPerInt
            for (offset in 0..bitsPerInt) {
                val bit = 1 shl offset
                if (word and bit != 0) {
                    val bitIndex = baseIndex + offset
                    if (bitIndex >= bitsPerId) {
                        return true
                    } else if (!f(index)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    // todo
    fun addKillsFromFlowExits() {}
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
}

class PropagationContext<O : DataFlowOperator>(val dataFlowContext: DataFlowContext<O>, val changed: Boolean)

enum class KillFrom {
    ScopeEnd, // e.g. a kill associated with the end of the scope of a variable declaration `let x;`
    Execution // e.g. a kill associated with an assignment statement `x = expr;`
}


