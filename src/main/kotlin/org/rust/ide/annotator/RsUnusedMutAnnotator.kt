/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.borrowCheckResult

class RsUnusedMutAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val function = element as? RsFunction ?: return

        val borrowCheckResult = function.borrowCheckResult
        val body = function.block ?: return
        val usedMut = HashSet<RsElement>(borrowCheckResult.usedMutNodes)

        val usedMutVisitor = UsedMutVisitor(holder, usedMut)
        usedMutVisitor.visitBlock(body)

        val unusedMutVisitor = UnusedMutVisitor(holder, usedMut)
        /*
        function.valueParameters.mapNotNull { it.pat }.forEach {
            unusedMutVisitor.checkUnusedMutPat(listOf(it))
        }
        */
        unusedMutVisitor.visitBlock(body)
    }

}

class UsedMutVisitor(val holder: AnnotationHolder, val set: MutableSet<RsElement>) : RsVisitor() {
    override fun visitBlock(block: RsBlock) {}
}

class UnusedMutVisitor(val holder: AnnotationHolder, val usedMut: MutableSet<RsElement>) : RsVisitor() {
    override fun visitBlock(block: RsBlock) {
        block.acceptChildren(this)
    }

    override fun visitStmt(stmt: RsStmt) {
        stmt.acceptChildren(this)
    }

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
                .filter { it.kind is RsBindingModeKind.BindByValue && it.mutability.isMut }
                .forEach { mutables.getOrPut(it.identifier.text, { mutableListOf() }).add(it) }
        }

        for ((_, elements) in mutables) {
            if (elements.any { usedMut.contains(it) }) {
                continue
            }

            val element = elements.first()
            val annotation = holder.createWarningAnnotation(element, "Mut is unused")
        }
    }
}


