/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.infer

import com.intellij.openapi.util.Computable
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.resolve.StdKnownItems
import org.rust.lang.core.types.builtinDeref
import org.rust.lang.core.types.infer.Aliasability.FreelyAliasable
import org.rust.lang.core.types.infer.Aliasability.NonAliasable
import org.rust.lang.core.types.infer.AliasableReason.*
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.BorrowKind.MutableBorrow
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.ImmutabilityBlame.*
import org.rust.lang.core.types.infer.InteriorKind.*
import org.rust.lang.core.types.infer.MutabilityCategory.Declared
import org.rust.lang.core.types.infer.PointerKind.BorrowedPointer
import org.rust.lang.core.types.infer.PointerKind.UnsafePointer
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.isDereference
import org.rust.lang.core.types.regions.ReStatic
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import org.rust.openapiext.recursionGuard
import org.rust.stdext.nextOrNull


/** [Categorization] is a subset of the full expression forms */
sealed class Categorization {
    /** Temporary value */
    data class Rvalue(val region: Region) : Categorization()

    /** Static value */
    object StaticItem : Categorization()

    /** Variable captured by closure */
    class Upvar : Categorization()

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
    }
}

sealed class PointerKind {
    data class BorrowedPointer(val borrowKind: BorrowKind, val region: Region) : PointerKind()
    data class UnsafePointer(val mutability: Mutability) : PointerKind()
}

sealed class InteriorKind {
    class InteriorField(val fieldName: String?) : InteriorKind()    // e.g. `s.field`
    object InteriorIndex : InteriorKind()                           // e.g. `arr[0]`
    object InteriorPattern : InteriorKind()                         // e.g. `fn foo([_, a, _, _]: [A; 4]) { ... }`
}

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
    Borrowed,
    Static,
    StaticMut
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
 * [element]: Expr
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
                // try to figure out where the immutable reference came from
                val pointerKind = category.pointerKind
                val baseCmt = category.cmt
                if (pointerKind is BorrowedPointer && pointerKind.borrowKind is ImmutableBorrow) {
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
                    is ImmutableBorrow -> FreelyAliasable(Borrowed)
                }
            category is StaticItem -> FreelyAliasable(if (isMutable) StaticMut else Static)
            category is Interior -> category.cmt.aliasability
            category is Downcast -> category.cmt.aliasability
            else -> NonAliasable
        }
}

class MemoryCategorizationContext(val infcx: RsInferenceContext) {
    fun process(element: RsInferenceContextOwner): RsMemoryCategorizationResult {
        val result = mutableMapOf<RsExpr, Cmt>()
        element.descendantsOfType<RsExpr>().map { result[it] = processExpr(it) }
        return result
    }

    fun processExpr(expr: RsExpr): Cmt {
        val adjustments = expr.inference?.adjustments?.get(expr) ?: emptyList()
        return processExprAdjustedWith(expr, adjustments.asReversed().iterator())
    }

    private fun processExprAdjustedWith(expr: RsExpr, adjustments: Iterator<Adjustment>): Cmt =
        when (adjustments.nextOrNull()) {
            is Adjustment.Deref -> {
                // TODO: overloaded deref
                processDeref(expr, processExprAdjustedWith(expr, adjustments))
            }
            else -> processExprUnadjusted(expr)
        }

    private fun processExprUnadjusted(expr: RsExpr): Cmt =
        when (expr) {
            is RsUnaryExpr -> processUnaryExpr(expr)
            is RsDotExpr -> processDotExpr(expr)
            is RsIndexExpr -> processIndexExpr(expr)
            is RsPathExpr -> processPathExpr(expr)
            is RsParenExpr -> processParenExpr(expr)
            else -> processRvalue(expr)
        }

    private fun processUnaryExpr(unaryExpr: RsUnaryExpr): Cmt {
        if (!unaryExpr.isDereference) return processRvalue(unaryExpr)
        val base = unaryExpr.expr ?: return Cmt(unaryExpr, ty = unaryExpr.type)
        val baseCmt = processExpr(base)
        return processDeref(unaryExpr, baseCmt)
    }

    private fun processDotExpr(dotExpr: RsDotExpr): Cmt {
        if (dotExpr.methodCall != null) {
            return processRvalue(dotExpr)
        }
        val type = dotExpr.type
        val base = dotExpr.expr
        val baseCmt = processExpr(base)
        val fieldName = dotExpr.fieldLookup?.identifier?.text
        return cmtOfField(dotExpr, baseCmt, fieldName, type)
    }

    private fun processIndexExpr(indexExpr: RsIndexExpr): Cmt {
        val type = indexExpr.type
        val base = indexExpr.containerExpr ?: return Cmt(indexExpr, ty = type)
        val baseCmt = processExpr(base)
        return Cmt(indexExpr, Interior(baseCmt, InteriorIndex), baseCmt.mutabilityCategory.inherit(), type)
    }

    private fun processPathExpr(pathExpr: RsPathExpr): Cmt {
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

    private fun processParenExpr(parenExpr: RsParenExpr): Cmt =
        processExpr(parenExpr.expr)


    private fun processDeref(expr: RsExpr, baseCmt: Cmt): Cmt {
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

    fun walkPat(cmt: Cmt, pat: RsPat, callback: (Cmt, RsPat) -> Unit) {
        fun processTuplePats(pats: List<RsPat>) {
            for ((i, subPat) in pats.withIndex()) {
                val subType = subPat.descendantsOfType<RsPatBinding>().firstOrNull()?.type ?: continue
                val interior = InteriorField(i.toString())
                val subCmt = Cmt(pat, Interior(cmt, interior), cmt.mutabilityCategory.inherit(), subType)
                walkPat(subCmt, subPat, callback)
            }
        }

        callback(cmt, pat)

        when (pat) {
            is RsPatIdent -> pat.pat?.let { walkPat(cmt, it, callback) }

            is RsPatTupleStruct -> processTuplePats(pat.patList)

            is RsPatTup -> processTuplePats(pat.patList)

            is RsPatStruct -> {
                for (patField in pat.patFieldList) {
                    val fieldType = patField.patBinding?.type ?: continue
                    val fieldName = patField.identifier?.text ?: continue
                    val fieldPat = patField.pat ?: continue
                    val fieldCmt = cmtOfField(pat, cmt, fieldName, fieldType)
                    walkPat(fieldCmt, fieldPat, callback)
                }
            }

            is RsPatSlice -> {
                val elementCmt = cmtOfSliceElement(pat, cmt)
                pat.patList.forEach { walkPat(elementCmt, it, callback) }
            }
        }
    }

    private fun cmtOfField(element: RsElement, baseCmt: Cmt, fieldName: String?, fieldType: Ty): Cmt =
        Cmt(
            element,
            Interior(baseCmt, InteriorField(fieldName)),
            baseCmt.mutabilityCategory.inherit(),
            fieldType
        )

    private fun cmtOfSliceElement(element: RsElement, baseCmt: Cmt): Cmt =
        Cmt(
            element,
            Interior(baseCmt, InteriorPattern),
            baseCmt.mutabilityCategory.inherit(),
            baseCmt.ty
        )

    fun isTypeMovesByDefault(ty: Ty): Boolean =
        infcx.lookup.isCopy(ty).not()
}

typealias RsMemoryCategorizationResult = MutableMap<RsExpr, Cmt>

fun computeCategorizationIn(element: RsInferenceContextOwner): RsMemoryCategorizationResult {
    val items = StdKnownItems.relativeTo(element)
    val lookup = ImplLookup(element.project, items)
    val mc = MemoryCategorizationContext(lookup.ctx)
    return recursionGuard(element, Computable { mc.process(element) })
        ?: error("Can not run nested categorization")
}
