/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsVisitor
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.livenessAnalysisResult

class RsLivenessInspection : RsLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        object : RsVisitor() {
            override fun visitFunction(func: RsFunction) {
                val livenessAnalysisResult = func.livenessAnalysisResult ?: return

                livenessAnalysisResult.unusedVariables.forEach {
                    registerUnusedVariableProblem(holder, it)
                }
                livenessAnalysisResult.unusedArguments.forEach {
                    registerUnusedArgumentProblem(holder, it)
                }
                livenessAnalysisResult.deadAssignments.forEach {
                    registerDeadAssignment(holder, it)
                }
            }
        }

    private fun registerUnusedArgumentProblem(holder: ProblemsHolder, declaration: RsElement) {
        holder.registerProblem(declaration, "Unused parameter", ProblemHighlightType.WARNING)
    }

    private fun registerUnusedVariableProblem(holder: ProblemsHolder, declaration: RsElement) {
        holder.registerProblem(declaration, "Unused variable", ProblemHighlightType.WARNING)
    }

    private fun registerDeadAssignment(holder: ProblemsHolder, assignment: RsElement) {
        holder.registerProblem(assignment, "Dead assignment", ProblemHighlightType.WARNING)
    }
}
