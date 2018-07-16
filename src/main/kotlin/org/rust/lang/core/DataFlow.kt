/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

enum class EntryOrExit { Entry, Exit }

class DataFlowContext<O : DataFlowOperator>(val analysisName: String, val oper: O, val bitsPerId: Int, val wordsPerId: Int) {
    val gens: MutableList<Int> = mutableListOf()
    val scopeKills: MutableList<Int> = mutableListOf()
    val actionKills: MutableList<Int> = mutableListOf()
    val onEntry: MutableList<Int> = mutableListOf()
}

interface BitwiseOperator {
    fun join(succ: Int, pred: Int): Int
}

interface DataFlowOperator : BitwiseOperator {
    val initialValue: Boolean
}

class PropagationContext<O : DataFlowOperator>(val dataFlowContext: DataFlowContext<O>, val changed: Boolean)

