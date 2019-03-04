/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.utils

import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.ComparisonOp.*
import org.rust.lang.core.psi.ext.EqualityOp.EQ
import org.rust.lang.core.psi.ext.EqualityOp.EXCLEQ
import org.rust.lang.core.psi.ext.operatorType

fun RsBinaryExpr.negateToString(): String {
    val lhs = left.text
    val rhs = right?.text ?: ""
    val op = when (operatorType) {
        EQ -> "!="
        EXCLEQ -> "=="
        GT -> "<="
        LT -> ">="
        GTEQ -> "<"
        LTEQ -> ">"
        else -> null
    }
    return if (op != null) "$lhs $op $rhs" else "!($text)"
}

fun PsiElement.isNegation(): Boolean =
    this is RsUnaryExpr && excl != null

fun PsiElement.negate(): PsiElement {
    if (isNegation()) {
        val inner = (this as RsUnaryExpr).expr!!
        return (inner as? RsParenExpr)?.expr ?: inner
    }

    val psiFactory = RsPsiFactory(project)
    return when (this) {
        is RsBinaryExpr ->
            psiFactory.createExpression(negateToString())

        is RsParenExpr ->
            psiFactory.createExpression(expr.negate().text)

        is RsPathExpr, is RsCallExpr ->
            psiFactory.createExpression("!$text")

        else ->
            psiFactory.createExpression("!($text)")
    }
}
