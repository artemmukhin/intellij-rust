/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.checkMatch

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type

class CheckMatchException(message: String) : Exception(message)

typealias Matrix = List<List<Pattern>>

/**
 * Returns the type of elements of the matrix
 *
 * @returns [TyUnknown] if there is more than one distinct known type
 */
val Matrix.type: Ty
    get() = asSequence().flatten()
        .map { it.ty }.filter { it !is TyUnknown }
        .distinct().singleOrNull()
        ?: TyUnknown

@Throws(CheckMatchException::class)
fun List<RsMatchArm>.calculateMatrix(): Matrix =
    flatMap { arm -> arm.patList.map { listOf(it.lower) } }

private val RsExpr.value: Constant
    get() = when (this) {
        is RsLitExpr -> {
            val kind = kind
            when (kind) {
                is RsLiteralKind.Boolean -> Constant.Boolean(kind.value)
                is RsLiteralKind.Integer -> Constant.Integer(kind.value ?: 0)
                is RsLiteralKind.Float -> Constant.Float(kind.value ?: 0.0)
                is RsLiteralKind.String -> Constant.String(kind.value ?: "")
                is RsLiteralKind.Char -> Constant.Char(kind.value ?: "")
                null -> Constant.Unknown
            }
        }
        is RsPathExpr -> TODO()
        is RsUnaryExpr -> TODO()
        else -> TODO()
    }

private val RsPat.type: Ty
    get() = when (this) {
        is RsPatConst -> expr.type
        is RsPatStruct, is RsPatTupleStruct -> {
            val path = (this as? RsPatTupleStruct)?.path ?: (this as RsPatStruct).path
            when (val resolved = path.reference.resolve()) {
                is RsEnumVariant -> TyAdt.valueOf(resolved.parentEnum)
                is RsStructItem -> TyAdt.valueOf(resolved)
                else -> TyUnknown
            }
        }
        is RsPatWild -> TyUnknown
        is RsPatIdent -> when (val resolved = patBinding.reference.resolve()) {
            is RsEnumVariant -> TyAdt.valueOf(resolved.parentEnum)
            is RsConstant -> patBinding.type
            else -> patBinding.type
        }
        is RsPatTup -> TyTuple(patList.map { it.type })
        is RsPatRange -> patConstList.firstOrNull()?.type ?: TyUnknown

        is RsPatRef -> TyReference(pat.type, Mutability.valueOf(mut != null))
        is RsPatMacro -> TODO()
        is RsPatSlice -> TODO()
        else -> TODO()
    }

// lower_pattern_unadjusted
private val RsPat.kind: PatternKind
    get() = when (this) {
        is RsPatIdent -> {
            if (pat != null) throw TODO("Support `x @ pat`")
            when (val resolved = patBinding.reference.resolve()) {
                is RsEnumVariant -> PatternKind.Variant(resolved.parentEnum, resolved, emptyList())
                is RsConstant -> PatternKind.Const(resolved.expr?.value ?: Constant.Unknown)
                else -> PatternKind.Binding(patBinding.type, patBinding.name.orEmpty())
            }
        }

        is RsPatWild -> PatternKind.Wild

        is RsPatTup -> PatternKind.Leaf(patList.map { it.lower })

        is RsPatStruct -> {
            val item = path.reference.resolve() as? RsFieldsOwner
                ?: throw CheckMatchException("Can't resolve ${path.text}")
            val indices = item.namedFields.withIndex().associateBy({ it.value.name }, { it.index })

            val subPatterns = patFieldList
                .sortedBy { indices[it.patFieldFull?.referenceNameElement?.text] }
                .map { patField ->
                    val pat = patField.patFieldFull?.pat
                    val binding = patField.patBinding
                    pat?.lower
                        ?: binding?.type?.let { ty -> Pattern(ty, PatternKind.Binding(ty, binding.name.orEmpty())) }
                        ?: throw CheckMatchException("Binding type = null")
                }

            getLeafOrVariant(item, subPatterns)
        }

        is RsPatTupleStruct -> {
            val item = path.reference.resolve() ?: throw CheckMatchException("Can't resolve ${path.text}")
            val subPatterns = patList.map { it.lower }

            getLeafOrVariant(item, subPatterns)
        }

        is RsPatConst -> {
            val ty = expr.type
            if (ty is TyAdt) {
                if (ty.item is RsEnumItem) {
                    val variant = (expr as RsPathExpr).path.reference.resolve() as RsEnumVariant
                    PatternKind.Variant(ty.item, variant, emptyList())
                } else {
                    throw CheckMatchException("Unresolved constant")
                }
            } else {
                PatternKind.Const(expr.value)
            }
        }

        is RsPatRange -> {
            val a = patConstList.getOrNull(0) ?: throw CheckMatchException("Incomplete range")
            val b = patConstList.getOrNull(1) ?: throw CheckMatchException("Incomplete range")
            PatternKind.Range(a.expr.value, b.expr.value, isInclusive)
        }

        is RsPatRef -> PatternKind.Deref(pat.lower)
        is RsPatMacro -> TODO()
        is RsPatSlice -> TODO()
        else -> TODO()
    }

// lower_variant_or_leaf
private fun getLeafOrVariant(item: RsElement, subPatterns: List<Pattern>): PatternKind =
    when (item) {
        is RsEnumVariant -> PatternKind.Variant(item.parentEnum, item, subPatterns)
        is RsStructItem -> PatternKind.Leaf(subPatterns)
        else -> throw CheckMatchException("Impossible case $item")
    }

private val RsPat.lower: Pattern
    get() = Pattern(type, kind)
