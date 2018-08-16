/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck.gatherLoans

import org.rust.lang.core.psi.RsStmt
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.lang.core.types.borrowck.BorrowCheckContext
import org.rust.lang.core.types.infer.Cmt

class MoveErrorCollector(private val errors: MutableList<MoveError> = mutableListOf()) {
    fun addError(error: MoveError) =
        errors.add(error)

    fun reportPotentialErrors(bccx: BorrowCheckContext) {
    }

    fun reportErrors() {
        errors.forEach { error ->
            println("###MoveError###")
            println("${error.from.element.ancestorOrSelf<RsStmt>()?.text}")
            println()
        }
    }
}

class MoveError(val from: Cmt, val to: MovePlace?)

class MovePlace(val name: RsNamedElement, patternSource: PatternSource)
