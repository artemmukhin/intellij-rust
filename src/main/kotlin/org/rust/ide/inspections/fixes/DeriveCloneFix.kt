/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.fixes

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.core.psi.RsOuterAttr
import org.rust.lang.core.psi.RsPathExpr
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsStructOrEnumItemElement
import org.rust.lang.core.psi.ext.containingCargoPackage
import org.rust.lang.core.psi.ext.findOuterAttr
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.resolve.StdKnownItems
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.type

class DeriveCloneFix(element: RsElement) : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = name
    override fun getText(): String = "Derive Copy trait"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val type = (startElement as? RsPathExpr)?.type ?: return
        val structItem = ((type as? TyAdt)?.item as? RsStructItem) ?: return
        if (structItem.containingCargoPackage?.origin != PackageOrigin.WORKSPACE) return
        val keyword = structItem.vis ?: structItem.struct ?: return

        val psiFactory = RsPsiFactory(project)
        val existingDeriveAttr = structItem.findOuterAttr("derive")

        val newDeriveAttr =
            if (existingDeriveAttr != null) {
                updateDeriveAttr(psiFactory, existingDeriveAttr)
            } else {
                psiFactory.createOuterAttr("derive(Clone, Copy)")
            }

        structItem.addBefore(newDeriveAttr, keyword)
        reformat(project, structItem, newDeriveAttr)
    }

    private fun updateDeriveAttr(
        psiFactory: RsPsiFactory,
        deriveAttr: RsOuterAttr
    ): RsOuterAttr {
        val metaItemArgs = deriveAttr.metaItem.metaItemArgs?.metaItemList ?: return deriveAttr
        val oldItems = metaItemArgs.mapNotNull { it.identifier?.text }
        val oldItemsText = oldItems.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ", ") ?: ""

        try {
            deriveAttr.delete()
        } catch (e: IncorrectOperationException) {
            return deriveAttr
        }

        return if (metaItemArgs.any { it.identifier?.text == "Clone" }) {
            psiFactory.createOuterAttr("derive(${oldItemsText}Copy)")
        } else {
            psiFactory.createOuterAttr("derive(${oldItemsText}Clone, Copy)")
        }
    }

    private fun reformat(project: Project, item: RsStructOrEnumItemElement, deriveAttr: RsOuterAttr) {
        val marker = Object()
        PsiTreeUtil.mark(deriveAttr, marker)
        val reformattedItem = CodeStyleManager.getInstance(project).reformat(item)
        PsiTreeUtil.releaseMark(reformattedItem, marker)
    }

    companion object {
        fun createIfCompatible(element: RsElement): DeriveCloneFix? {
            val pathExpr = (element as? RsPathExpr) ?: return null
            val type = (pathExpr as? RsPathExpr)?.type ?: return null
            val structItem = ((type as? TyAdt)?.item as? RsStructItem) ?: return null
            if (structItem.containingCargoPackage?.origin != PackageOrigin.WORKSPACE) return null

            val implLookup = ImplLookup(element.project, StdKnownItems.relativeTo(element))
            val fieldTypes = structItem.blockFields?.fieldDeclList?.mapNotNull { it.typeReference?.type } ?: return null
            if (fieldTypes.any { !implLookup.isCopy(it) }) return null

            return DeriveCloneFix(pathExpr)
        }
    }
}
