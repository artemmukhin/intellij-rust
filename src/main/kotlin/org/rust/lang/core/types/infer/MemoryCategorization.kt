/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.infer

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.containerExpr
import org.rust.lang.core.psi.ext.mutability
import org.rust.lang.core.types.builtinDeref
import org.rust.lang.core.types.infer.Aliasability.FreelyAliasable
import org.rust.lang.core.types.infer.Aliasability.NonAliasable
import org.rust.lang.core.types.infer.AliasableReason.*
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.BorrowKind.MutableBorrow
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.ImmutabilityBlame.*
import org.rust.lang.core.types.infer.InteriorKind.InteriorElement
import org.rust.lang.core.types.infer.InteriorKind.InteriorField
import org.rust.lang.core.types.infer.MutabilityCategory.Declared
import org.rust.lang.core.types.infer.PointerKind.BorrowedPointer
import org.rust.lang.core.types.infer.PointerKind.UnsafePointer
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.isDereference
import org.rust.lang.core.types.regions.ReStatic
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.regions.ScopeTree
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import org.rust.stdext.nextOrNull


/** [Categorization] is a subset of the full expression forms */
sealed class Categorization {
    /** Temporary value */
    data class Rvalue(val region: Region) : Categorization()

    /** Static value */
    object StaticItem : Categorization()

    /** Variable captured by closure */
    object Upvar : Categorization()

    /** Local variable */
    data class Local(val element: RsElement) : Categorization()

    /** Dereference of a pointer */
    data class Deref(val cmt: Cmt, val pointerKind: PointerKind) : Categorization()

    /** Something reachable from the base without a pointer dereference (e.g. field) */
    data class Interior(val cmt: Cmt, val interiorKind: InteriorKind) : Categorization()

    /** Selects a particular enum variant (if enum has more than one variant */
    data class Downcast(val cmt: Cmt, val element: RsElement) : Categorization()
}

sealed class BorrowKind {
    object ImmutableBorrow : BorrowKind()
    object MutableBorrow : BorrowKind()

    companion object {
        fun from(mutability: Mutability): BorrowKind =
            when (mutability) {
                Mutability.IMMUTABLE -> ImmutableBorrow
                Mutability.MUTABLE -> MutableBorrow
            }

        fun isCompatible(firstKind: BorrowKind, secondKind: BorrowKind): Boolean =
            firstKind == ImmutableBorrow && secondKind == ImmutableBorrow
    }
}

sealed class PointerKind {
    data class BorrowedPointer(val borrowKind: BorrowKind, val region: Region) : PointerKind()
    data class UnsafePointer(val mutability: Mutability) : PointerKind()
}

sealed class InteriorKind {
    class InteriorField(val fieldIndex: FieldIndex? = null, val fieldName: String? = null) : InteriorKind()
    class InteriorElement(val offsetKind: InteriorOffsetKind) : InteriorKind()
}

enum class InteriorOffsetKind {
    Index,
    Pattern
}

class FieldIndex(index: Int, name: String?)

sealed class ImmutabilityBlame {
    class ImmutableLocal(val element: RsElement) : ImmutabilityBlame()
    object ClosureEnv : ImmutabilityBlame()
    class LocalDeref(val element: RsElement) : ImmutabilityBlame()
    object AdtFieldDeref : ImmutabilityBlame()
}

sealed class Aliasability {
    class FreelyAliasable(val reason: AliasableReason) : Aliasability()
    object NonAliasable : Aliasability()
    class ImmutableUnique(val aliasability: Aliasability) : Aliasability()
}

enum class AliasableReason {
    AliasableBorrowed,
    AliasableStatic,
    AliasableStaticMut
}


/** Mutability of the expression address */
enum class MutabilityCategory {
    /** Any immutable */
    Immutable,
    /** Directly declared as mutable */
    Declared,
    /** Inherited from the fact that owner is mutable */
    Inherited;

    companion object {
        fun from(mutability: Mutability): MutabilityCategory =
            when (mutability) {
                Mutability.IMMUTABLE -> Immutable
                Mutability.MUTABLE -> Declared
            }

        fun from(borrowKind: BorrowKind): MutabilityCategory =
            when (borrowKind) {
                is ImmutableBorrow -> Immutable
                is MutableBorrow -> Declared
            }

        fun from(pointerKind: PointerKind): MutabilityCategory =
            when (pointerKind) {
                is BorrowedPointer -> from(pointerKind.borrowKind)
                is UnsafePointer -> from(pointerKind.mutability)
            }
    }

    fun inherit(): MutabilityCategory =
        when (this) {
            Immutable -> Immutable
            Declared, Inherited -> Inherited
        }

    val isMutable: Boolean
        get() = when (this) {
            Immutable -> false
            Declared, Inherited -> true
        }
}

/**
 * [Cmt]: Category, MutabilityCategory, and Type
 *
 * Imagine a routine Address(Expr) that evaluates an expression and returns an
 * address where the result is to be found.  If Expr is a place, then this
 * is the address of the place.  If Expr is an rvalue, this is the address of
 * some temporary spot in memory where the result is stored.
 *
 * [category]: kind of Expr
 * [mutabilityCategory]: mutability of Address(Expr)
 * [ty]: the type of data found at Address(Expr)
 */
class Cmt(
    val element: RsElement,
    val category: Categorization? = null,
    val mutabilityCategory: MutabilityCategory = MutabilityCategory.from(Mutability.DEFAULT_MUTABILITY),
    val ty: Ty
) {
    val immutabilityBlame: ImmutabilityBlame? =
        when (category) {
            is Deref -> {
                val pointerKind = category.pointerKind
                val baseCmt = category.cmt
                if (pointerKind is BorrowedPointer && pointerKind.borrowKind === ImmutableBorrow) {
                    when (baseCmt.category) {
                        is Local -> LocalDeref(baseCmt.category.element)
                        is Interior -> AdtFieldDeref
                        is Upvar -> ClosureEnv
                        else -> null
                    }
                } else if (pointerKind is UnsafePointer) {
                    null
                } else {
                    baseCmt.immutabilityBlame
                }
            }
            is Local -> ImmutableLocal(category.element)
            is Interior -> category.cmt.immutabilityBlame
            is Downcast -> category.cmt.immutabilityBlame
            else -> null
        }

    val isMutable: Boolean get() = mutabilityCategory.isMutable

    val aliasability: Aliasability
        get() = when {
            category is Deref && category.pointerKind is BorrowedPointer ->
                when (category.pointerKind.borrowKind) {
                    is MutableBorrow -> category.cmt.aliasability
                    is ImmutableBorrow -> FreelyAliasable(AliasableBorrowed)
                }
            category is StaticItem -> FreelyAliasable(if (isMutable) AliasableStaticMut else AliasableStatic)
            category is Interior -> category.cmt.aliasability
            category is Downcast -> category.cmt.aliasability
            else -> NonAliasable
        }
}

class MemoryCategorizationContext(
    val regionScopeTree: ScopeTree? = null,
    val infcx: RsInferenceContext? = null
) {
    fun processUnaryExpr(unaryExpr: RsUnaryExpr): Cmt {
        if (!unaryExpr.isDereference) return processRvalue(unaryExpr)
        val base = unaryExpr.expr ?: return Cmt(unaryExpr, ty = unaryExpr.type)
        val baseCmt = processExpr(base)
        return processDeref(unaryExpr, baseCmt)
    }

    fun processDotExpr(dotExpr: RsDotExpr): Cmt {
        if (dotExpr.methodCall != null) {
            return processRvalue(dotExpr)
        }
        val type = dotExpr.type
        val base = dotExpr.expr
        val baseCmt = processExpr(base)
        return Cmt(dotExpr, Interior(baseCmt, InteriorField()), baseCmt.mutabilityCategory.inherit(), type)
    }

    fun cmtOfField(element: RsElement, baseCmt: Cmt, fieldName: String, fieldType: Ty): Cmt =
        Cmt(
            element,
            Interior(baseCmt, InteriorField(fieldName = fieldName)),
            baseCmt.mutabilityCategory.inherit(),
            fieldType
        )

    fun processIndexExpr(indexExpr: RsIndexExpr): Cmt {
        val type = indexExpr.type
        val base = indexExpr.containerExpr ?: return Cmt(indexExpr, ty = type)
        val baseCmt = processExpr(base)
        return Cmt(indexExpr, Interior(baseCmt, InteriorElement(InteriorOffsetKind.Index)), baseCmt.mutabilityCategory.inherit(), type)
    }

    fun processPathExpr(pathExpr: RsPathExpr): Cmt {
        val type = pathExpr.type
        val declaration = pathExpr.path.reference.resolve() ?: return Cmt(pathExpr, ty = type)
        return when (declaration) {
            is RsConstant -> {
                if (declaration.static != null) {
                    Cmt(pathExpr, StaticItem, MutabilityCategory.from(declaration.mutability), type)
                } else {
                    processRvalue(pathExpr)
                }
            }

            is RsEnumVariant, is RsStructItem, is RsFunction -> processRvalue(pathExpr)

            is RsPatBinding -> Cmt(pathExpr, Local(declaration), MutabilityCategory.from(declaration.mutability), type)

            is RsSelfParameter -> Cmt(pathExpr, Local(declaration), MutabilityCategory.from(declaration.mutability), type)

            else -> Cmt(pathExpr, ty = type)
        }
    }

    fun processParenExpr(parenExpr: RsParenExpr): Cmt =
        processExpr(parenExpr.expr)

    fun processExpr(expr: RsExpr): Cmt {
        val adjustments = expr.inference?.adjustments?.get(expr) ?: emptyList()
        return processExprAdjustedWith(expr, adjustments.asReversed().iterator())
    }

    fun processExprAdjustedWith(expr: RsExpr, adjustments: Iterator<Adjustment>): Cmt =
        when (adjustments.nextOrNull()) {
            is Adjustment.Deref -> {
                // TODO: overloaded deref
                processDeref(expr, processExprAdjustedWith(expr, adjustments))
            }
            else -> processExprUnadjusted(expr)
        }

    fun processDeref(expr: RsExpr, baseCmt: Cmt): Cmt {
        val baseType = baseCmt.ty
        val (derefType, derefMut) = baseType.builtinDeref() ?: Pair(TyUnknown, Mutability.DEFAULT_MUTABILITY)

        val pointerKind = when (baseType) {
            is TyReference -> BorrowedPointer(BorrowKind.from(baseType.mutability), baseType.region)
            is TyPointer -> UnsafePointer(baseType.mutability)
            else -> UnsafePointer(derefMut)
        }

        return Cmt(expr, Deref(baseCmt, pointerKind), MutabilityCategory.from(pointerKind), derefType)
    }

    // `rvalue_promotable_map` is needed to distinguish rvalues with static region and rvalue with temporary region,
    // so now all rvalues have static region
    fun processRvalue(expr: RsExpr): Cmt =
        Cmt(expr, Rvalue(ReStatic), Declared, expr.type)

    fun processRvalue(element: RsElement, tempScope: Region, ty: Ty): Cmt =
        Cmt(element, Rvalue(tempScope), Declared, ty)

    fun processPattern(cmt: Cmt, pat: RsPat, callback: (Cmt, RsPat) -> Unit): Boolean {
        val adjustmentsCount = pat.inference?.adjustments?.get(pat)?.size ?: 0

        var cmt = cmt
        repeat(adjustmentsCount) {
            cmt = processDeref(pat, cmt)
        }
        callback(cmt, pat)

        when (pat) {
            is RsPatTupleStruct -> {
            }

            is RsPatStruct -> {
                for (patField in pat.patFieldList) {
                    val fieldType = patField.patBinding?.type ?: continue
                    val fieldName = patField.identifier?.text ?: continue
                    val fieldPat = patField.pat ?: continue

                    val fieldCmt = cmtOfField(pat, cmt, fieldName, fieldType)
                    processPattern(fieldCmt, fieldPat, callback)
                }
            }

            is RsPatIdent -> pat.pat?.let { processPattern(cmt, it, callback) }

            is RsPatTuple -> {
            }

            is RsPatSlice -> {
            }
        }

        return true
    }

    fun processExprUnadjusted(expr: RsExpr): Cmt =
        when (expr) {
            is RsUnaryExpr -> processUnaryExpr(expr)
            is RsDotExpr -> processDotExpr(expr)
            is RsIndexExpr -> processIndexExpr(expr)
            is RsPathExpr -> processPathExpr(expr)
            is RsParenExpr -> processParenExpr(expr)
            else -> processRvalue(expr)
        }

    fun isTypeMovesByDefault(ty: Ty): Boolean =
        infcx?.lookup?.isCopy(ty)?.not() ?: true
}


val RsExpr.cmt: Cmt?
    get() = MemoryCategorizationContext().processExpr(this)

val RsExpr.mutabilityCategory: MutabilityCategory?
    get() = cmt?.mutabilityCategory
