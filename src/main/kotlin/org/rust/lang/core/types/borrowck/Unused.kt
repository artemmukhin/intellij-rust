/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

fun check(bccx: BorrowCheckContext, function: RsFunction) {
    val body = function.block ?: return
    val usedMut = HashSet<RsElement>(bccx.usedMutNodes)

    val usedMutVisitor = UsedMutVisitor(bccx, usedMut)
    usedMutVisitor.visitBlock(body)

    val unusedMutVisitor = UnusedMutVisitor(bccx, usedMut)
    function.valueParameters.mapNotNull { it.pat }.forEach {
        unusedMutVisitor.checkUnusedMutPat(listOf(it))
    }
    unusedMutVisitor.visitBlock(body)
}

class UsedMutVisitor(val bccx: BorrowCheckContext, val set: MutableSet<RsElement>) : RsVisitor()

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
            // reportWarning(element)
        }
    }
}


