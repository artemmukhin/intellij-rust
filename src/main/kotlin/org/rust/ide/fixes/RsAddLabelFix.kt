/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.fixes

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.RsWhileExpr
import org.rust.lang.core.psi.ext.RsLabelReferenceOwner
import org.rust.lang.core.psi.ext.ancestorStrict

class RsAddLabelFix(element: RsLabelReferenceOwner): LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = "Add label"

    override fun getText(): String = familyName

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, labelOwner: PsiElement, endElement: PsiElement) {
        if (editor == null) return
        val whileExpr = labelOwner.ancestorStrict<RsWhileExpr>() ?: return
        val labelDeclaration = RsPsiFactory(project).createLabelDeclaration("a")
        whileExpr.addBefore(labelDeclaration, whileExpr.firstChild)
        labelOwner.add(RsPsiFactory(project).createLabel("a"))
    }
}
