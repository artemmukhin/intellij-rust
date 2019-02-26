/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import org.rust.ide.annotator.fixes.AddMutableFix
import org.rust.ide.inspections.fixes.DeriveCopyFix
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.borrowCheckResult
import org.rust.lang.core.types.isMutable
import org.rust.lang.core.types.ty.TyReference
import org.rust.lang.core.types.type

class RsBorrowCheckerInspection : RsLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        object : RsVisitor() {
            override fun visitMethodCall(o: RsMethodCall) {
                val fn = o.reference.resolve() as? RsFunction ?: return
                val receiver = o.receiver
                if (checkMethodRequiresMutable(receiver, fn)) {
                    registerProblem(holder, receiver, receiver)
                }
            }

            override fun visitUnaryExpr(unaryExpr: RsUnaryExpr) {
                val expr = unaryExpr.expr ?: return
                if (unaryExpr.operatorType == UnaryOperator.REF_MUT && !expr.isMutable) {
                    registerProblem(holder, expr, expr)
                }
            }

            override fun visitFunction(func: RsFunction) {
                val borrowCheckResult = func.borrowCheckResult ?: return

                borrowCheckResult.usesOfMovedValue.forEach {
                    registerUseOfMovedValueProblem(holder, it.use)
                }
                borrowCheckResult.usesOfUninitializedVariable.forEach {
                    registerUseOfUninitializedVariableProblem(holder, it.use)
                }
                borrowCheckResult.moveErrors.forEach {
                    val move = it.from.element.ancestorOrSelf<RsExpr>()
                    if (move != null) registerMoveProblem(holder, move)
                }
                borrowCheckResult.unusedVariables.forEach {
                    registerUnusedVariableProblem(holder, it)
                }
                borrowCheckResult.unusedArguments.forEach {
                    registerUnusedArgumentProblem(holder, it)
                }
                borrowCheckResult.deadAssignments.forEach {
                    registerDeadAssignment(holder, it)
                }
            }
        }

    private fun registerProblem(holder: ProblemsHolder, expr: RsExpr, nameExpr: RsExpr) {
        val fix = AddMutableFix.createIfCompatible(nameExpr).let { if (it == null) emptyArray() else arrayOf(it) }
        holder.registerProblem(expr, "Cannot borrow immutable local variable `${nameExpr.text}` as mutable", *fix)
    }

    private fun registerUseOfMovedValueProblem(holder: ProblemsHolder, use: RsElement) {
        val fix = DeriveCopyFix.createIfCompatible(use).let { if (it == null) emptyArray() else arrayOf(it) }
        holder.registerProblem(use, "Use of moved value", *fix)
    }

    private fun registerMoveProblem(holder: ProblemsHolder, element: RsElement) {
        holder.registerProblem(element, "Cannot move")
    }

    private fun registerUseOfUninitializedVariableProblem(holder: ProblemsHolder, use: RsElement) {
        holder.registerProblem(use, "Use of possibly uninitialized variable")
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

    private fun checkMethodRequiresMutable(receiver: RsExpr, fn: RsFunction): Boolean {
        if (!receiver.isMutable &&
            fn.selfParameter != null &&
            fn.selfParameter?.mutability?.isMut == true &&
            fn.selfParameter?.isRef == true) {
            val type = receiver.type
            return type !is TyReference || !type.mutability.isMut
        }
        return false
    }
}
