/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.ProblemsHolder
import org.rust.lang.core.CFG
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsStmt
import org.rust.lang.core.psi.RsVisitor

class RsUnreachableCodeInspection : RsLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        object : RsVisitor() {
            override fun visitBlock(block: RsBlock) {
                val cfg = CFG(block)
                val unreachableStatements = cfg.findUnreachableStmts()
                unreachableStatements.forEach { registerProblem(holder, it) }
            }
        }

    private fun registerProblem(holder: ProblemsHolder, statement: RsStmt) {
        holder.registerProblem(statement, "Unreachable statement `${statement.text}`")
    }
}
