/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.RsElement

enum class EntryOrExit { Entry, Exit }

class DataFlowContext<O : DataFlowOperator>(val analysisName: String,
                                            val body: RsBlock,
                                            val cfg: CFG,
                                            val oper: O,
                                            val bitsPerId: Int) {
    val bitsPerInt: Int = 32
    val wordsPerId: Int
    val gens: MutableList<Int>
    val scopeKills: MutableList<Int>
    val actionKills: MutableList<Int>
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


}

interface BitwiseOperator {
    fun join(succ: Int, pred: Int): Int
}

interface DataFlowOperator : BitwiseOperator {
    val initialValue: Boolean
}

class PropagationContext<O : DataFlowOperator>(val dataFlowContext: DataFlowContext<O>, val changed: Boolean)

enum class KillFrom {
    ScopeEnd, // e.g. a kill associated with the end of the scope of a variable declaration `let x;`
    Execution // e.g. a kill associated with an assignment statement `x = expr;`
}


