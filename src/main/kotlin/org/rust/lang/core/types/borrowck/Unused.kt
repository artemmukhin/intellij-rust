/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsBindingModeKind
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.ext.kind

fun check(bccx: BorrowCheckContext, body: RsBlock) {
    var usedMut = bccx.usedMutNodes
    val finder = UsedMutVisitor(bccx, usedMut)
    finder.visit(body)
}

class UsedMutVisitor(val bccx: BorrowCheckContext, val set: MutableSet<RsElement>) : RsVisitor() {
}

class UnusedMutVisitor(val bccx: BorrowCheckContext, val usedMut: MutableSet<RsElement>) : RsVisitor() {
    override fun visitMatchArm(arm: RsMatchArm) {
        checkUnusedMutPat(arm.patList)
    }

    override fun visitLetDecl(letDecl: RsLetDecl) {
        letDecl.pat?.let { checkUnusedMutPat(listOf(it)) }
    }


    fun checkUnusedMutPat(pats: List<RsPat>) {
        val mutables = mutableMapOf<String, MutableList<RsPatBinding>>()
        for (pat in pats) {
            pat.descendantsOfType<RsPatBinding>()
                .filterNot { it.identifier.text.startsWith("_") }
                .filter { it.kind is RsBindingModeKind.BindByValue }
                .forEach { mutables.getOrDefault(it.identifier.text, mutableListOf()).add(it) }
        }

        for ((_, elements) in mutables) {
            if (elements.any { usedMut.contains(it) }) {
                continue
            }

            val element = elements.first()
            reportWarning(element)
        }
    }
}


