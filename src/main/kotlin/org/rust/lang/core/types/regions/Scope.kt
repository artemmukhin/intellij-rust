/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

import com.intellij.openapi.util.TextRange
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

data class Scope(val element: RsElement, val code: Int) {

    val data: ScopeData
        get() = when (code) {
            SCOPE_DATA_NODE -> ScopeData.Node(element)
            SCOPE_DATA_CALLSITE -> ScopeData.CallSite(element)
            SCOPE_DATA_ARGUMENTS -> ScopeData.Arguments(element)
            SCOPE_DATA_DESTRUCTION -> ScopeData.Destruction(element)
            else -> ScopeData.Remainder(BlockRemainder(element, code))
        }

    /**
     * Returns the span of this Scope.  Note that in general the returned span may not correspond to the span of any
     * element in the AST.
     */
    fun getSpan(scopeTree: ScopeTree): TextRange? {
        val block = element.enclosingBlockOrSelf ?: return DUMMY_SPAN
        return element.getStartOffsetIn(block)?.let { TextRange(it, it + element.textLength) }
    }

    companion object {
        val DUMMY_SPAN: TextRange = TextRange(0, 0)

        fun from(scopeData: ScopeData): Scope {
            val (element, code) = when (scopeData) {
                is ScopeData.Node -> Pair(scopeData.element, SCOPE_DATA_NODE)
                is ScopeData.CallSite -> Pair(scopeData.element, SCOPE_DATA_CALLSITE)
                is ScopeData.Arguments -> Pair(scopeData.element, SCOPE_DATA_ARGUMENTS)
                is ScopeData.Destruction -> Pair(scopeData.element, SCOPE_DATA_DESTRUCTION)
                is ScopeData.Remainder -> Pair(scopeData.remainder.block, scopeData.remainder.firstStatementIndex)
            }
            return Scope(element, code)
        }

        fun createNode(element: RsElement): Scope = from(ScopeData.Node(element))
        fun createCallSite(element: RsElement): Scope = from(ScopeData.CallSite(element))
        fun createArguments(element: RsElement): Scope = from(ScopeData.Arguments(element))
        fun createDestruction(element: RsElement): Scope = from(ScopeData.Destruction(element))
        fun createRemainder(remainder: BlockRemainder): Scope = from(ScopeData.Remainder(remainder))
    }
}


const val SCOPE_DATA_NODE: Int = 0.inv()
const val SCOPE_DATA_CALLSITE: Int = 1.inv()
const val SCOPE_DATA_ARGUMENTS: Int = 2.inv()
const val SCOPE_DATA_DESTRUCTION: Int = 3.inv()
const val SCOPE_DATA_REMAINDER_MAX: Int = 4.inv()

sealed class ScopeData {
    data class Node(val element: RsElement) : ScopeData()

    /** Scope of the call-site for a function or closure (outlives the arguments as well as the body). */
    data class CallSite(val element: RsElement) : ScopeData()

    /** Scope of arguments passed to a function or closure (they outlive its body). */
    data class Arguments(val element: RsElement) : ScopeData()

    /** Scope of destructors for temporaries of node-id. */
    data class Destruction(val element: RsElement) : ScopeData()

    /** Scope following a `let id = expr;` binding in a block. */
    data class Remainder(val remainder: BlockRemainder) : ScopeData()
}

/**
 * Represents a subScope of `block` for a binding that is introduced by `block.stmts[first_statement_index]`.
 * Such subScopes represent  a suffix of the block. Note that each subScope does not include the initializer expression,
 * if any, for the statement indexed by  `first_statement_index`.
 */
data class BlockRemainder(val block: RsElement, val firstStatementIndex: Int)

typealias ScopeDepth = Int
typealias ScopeInfo = Pair<Scope, ScopeDepth>

class ScopeTree {
    var rootBody: RsElement? = null
    var rootParent: RsElement? = null
    private val parentMap: MutableMap<Scope, ScopeInfo> = mutableMapOf()
    private val variableMap: MutableMap<RsElement, Scope> = mutableMapOf()
    private val destructionScopes: MutableMap<RsElement, Scope> = mutableMapOf()
    private val rvalueScopes: MutableMap<RsElement, Scope?> = mutableMapOf()
    private val closureTree: MutableMap<RsElement, RsElement> = mutableMapOf()

    fun recordScopeParent(child: Scope, parent: ScopeInfo?) {
        if (parent != null) {
            val previous = parentMap.put(child, parent)
            assert(previous == null)
        }

        // record the destruction scopes for later so we can query them
        val data = child.data
        if (data is ScopeData.Destruction) {
            destructionScopes[data.element] = child
        }
    }

    fun forEachEnclosingScope(action: (Scope, Scope) -> Unit) {
        for ((child, parentInfo) in parentMap) {
            action(child, parentInfo.first)
        }
    }

    fun forEachVariableScope(action: (RsElement, Scope) -> Unit) {
        for ((child, parent) in variableMap) {
            action(child, parent)
        }
    }

    fun getDestructionScope(element: RsElement): Scope? = destructionScopes[element]?.copy()

    /**
     * Records that `subClosure` is defined within `supClosure`. These ids should be the id of the block that is the
     * fn body, which is also the root of the region hierarchy for that fn.
     */
    fun recordClosureParent(subClosure: RsElement, supClosure: RsElement) {
        require(subClosure != supClosure)
        val previous = closureTree.put(subClosure, supClosure)
        assert(previous == null)
    }

    fun recordVariableScope(variable: RsElement, lifetime: Scope) {
        assert(variable != lifetime.element)
        variableMap[variable] = lifetime
    }

    fun recordRvalueScope(variable: RsElement, lifetime: Scope?) {
        if (lifetime != null) {
            assert(variable != lifetime.element)
        }
        rvalueScopes[variable] = lifetime
    }

    /** Returns the narrowest scope that encloses [scope], if any. */
    fun getEnclosingScope(scope: Scope): Scope? = parentMap[scope]?.copy()?.first

    /** Returns the lifetime of the local variable [variable]. */
    fun getVariableScope(variable: RsElement): Scope = checkNotNull(variableMap[variable]) { "No enclosing scope for element" }

    /** Returns the scope when temp created by [expr] will be cleaned up. */
    fun temporaryScope(expr: RsElement): Scope? {
        // check for a designated rvalue scope
        rvalueScopes[expr]?.let { return it }

        // else, locate the innermost terminating scope  if there's one. Static items, for instance, won't have an
        // enclosing scope, hence no scope will be returned.
        var current = Scope.createNode(expr)
        while (true) {
            val parent = parentMap[current]?.first ?: break
            if (parent.data is ScopeData.Destruction) {
                return current
            } else {
                current = parent
            }
        }

        return null
    }

    /** Returns the lifetime of the variable [element].*/
    fun getVariableRegion(element: RsElement): Region = ReScope(getVariableScope(element))

    fun areScopesIntersect(scope1: Scope, scope2: Scope): Boolean =
        isSubScopeOf(scope1, scope2) || isSubScopeOf(scope2, scope1)

    /** Returns true if [subScope] is equal to or is lexically nested inside [superScope] and false otherwise. */
    fun isSubScopeOf(subScope: Scope, superScope: Scope): Boolean {
        var scope = subScope
        while (scope != superScope) {
            scope = getEnclosingScope(scope) ?: return false
        }
        return true
    }

    /** Returns the element of the innermost containing body. */
    fun getContainingBody(scope: Scope): RsElement? {
        var scopeTemp = scope
        while (true) {
            (scopeTemp.data as? ScopeData.CallSite)?.let { return it.element }
            scopeTemp = getEnclosingScope(scopeTemp) ?: return null
        }
    }

    /**
     * Finds the nearest common ancestor of two scopes.
     * That is, finds the smallest scope which is greater than or equal to both [scope1] and [scope2].
     */
    fun getNearestCommonAncestor(scope1: Scope, scope2: Scope): Scope {
        if (scope1 == scope2) return scope1

        var scope1Temp = scope1
        var scope2Temp = scope2

        // Get the depth of each scope's parent.
        // If either scope has no parent, it must be the root, which means we can stop immediately because the root
        // must be the nearest common ancestor. (In practice, this is moderately common.)
        val (parent1, parent1Depth) = parentMap[scope1Temp] ?: return scope1Temp
        val (parent2, parent2Depth) = parentMap[scope2Temp] ?: return scope2Temp

        when {
            parent1Depth > parent2Depth -> {
                // `a` is lower than `b`. Move `a` up until it's at the same depth  as `b`. The first move up is trivial
                // because we already found `parent_a` above; the loop does the remaining N-1 moves.
                scope1Temp = parent1
                repeat(parent2Depth - parent1Depth - 1) {
                    scope1Temp = parentMap[scope1Temp]!!.first
                }
            }
            parent2Depth > parent1Depth -> {
                // `b` is lower than `a`.
                scope2Temp = parent2
                repeat(parent1Depth - parent2Depth - 1) {
                    scope2Temp = parentMap[scope2Temp]!!.first
                }
            }
            else -> {
                // Both scopes are at the same depth, and we know they're not equal  because that case was tested for at
                // the top of this function. So we can trivially move them both up one level now.
                assert(parent1Depth != 0)
                scope1Temp = parent1
                scope2Temp = parent2
            }
        }

        // Now both scopes are at the same level. We move upwards in lockstep  until they match. In practice, this loop
        // is almost always executed zero times because `a` is almost always a direct ancestor of `b` or vice versa.
        while (scope1Temp != scope2Temp) {
            scope1Temp = parentMap[scope1Temp]!!.first
            scope2Temp = parentMap[scope2Temp]!!.first
        }

        return scope1Temp
    }

    /**
     * Assuming that the provided region was defined within this [ScopeTree], returns the outermost [Scope] that the
     * region outlives.
     */
    fun getEarlyFreeScope(region: ReEarlyBound): Scope {
        val parameterOwner = region.parameter.stubAncestorStrict<RsGenericDeclaration>()
        val body = (parameterOwner as? RsFunction)?.block ?: run {
            // The lifetime was defined on node that doesn't own a body, which in practice can only mean a trait or an
            // impl, that is the parent of a method, and that is enforced below.
            assert(parameterOwner == rootParent)

            // The trait/impl lifetime is in scope for the method's body.
            rootBody!!
        }
        return Scope.createCallSite(body)
    }

    /**
     * Assuming that the provided region was defined within this [ScopeTree], returns the outermost [Scope] that the
     * region outlives.
     */
    fun getFreeScope(region: ReFree): Scope {
        val parameterOwner = if (region.boundRegion is BoundRegion.Named) {
            region.boundRegion.element.stubAncestorStrict<RsGenericDeclaration>()!!
        } else {
            region.element
        }

        // Ensure that the named late-bound lifetimes were defined on the same function that they ended up being freed in.
        assert(parameterOwner === region.element)

        val body = checkNotNull((parameterOwner as? RsFunction)?.block)
        return Scope.createCallSite(body)
    }
}

data class Context(
    val root: RsElement? = null,
    var variableParent: ScopeInfo? = null,
    var parent: ScopeInfo? = null
)

/** Records the lifetime of a local variable as `cx.var_parent`. */
fun recordVariableLifetime(visitor: RegionResolutionVisitor, variable: RsElement, span: TextRange) {
    visitor.context.variableParent?.let { (parentScope, _) ->
        visitor.scopeTree.recordVariableScope(variable, parentScope)
    }
}

fun resolveBlock(visitor: RegionResolutionVisitor, block: RsBlock) {
    val prevContext = visitor.context.copy()

    visitor.enterNodeScopeWithDestructor(block)
    visitor.context.variableParent = visitor.context.parent

    for ((i, statement) in block.childrenOfType<RsElement>().withIndex()) {
        if (statement is RsItemElement || statement is RsLetDecl) {
//            visitor.enterScope(Scope.createRemainder(BlockRemainder(block FirstStatementIndex.new(i))))
            visitor.context.variableParent = visitor.context.parent
        }
        statement.accept(visitor)
    }

    visitor.context = prevContext
}

fun resolveArm(visitor: RegionResolutionVisitor, arm: RsMatchArm) {
//    visitor.terminatingScopes.insert(arm.expr)

    arm.matchArmGuard?.expr?.let {
        //        visitor.terminatingScopes.insert(it)
    }

//    arm.accept(visitor)
}

fun resolvePat(visitor: RegionResolutionVisitor, pat: RsPat) {
    visitor.recordChildScope(Scope.createNode(pat))

    // If this is a binding then record the lifetime of that binding.
    if (pat is RsPatIdent) {
        recordVariableLifetime(visitor, pat, pat.textRange)
    }

//    pat.accept(visitor)

}

class RegionResolutionVisitor(val scopeTree: ScopeTree, var context: Context) : RsVisitor() {

    /**
     * Records the current parent (if any) as the parent of [childScope].
     * Returns the depth of [childScope].
     */
    fun recordChildScope(childScope: Scope): ScopeDepth {
        val parentInfo = context.parent
        scopeTree.recordScopeParent(childScope, parentInfo)
        return parentInfo?.let { (_, depth) -> depth + 1 } ?: 1
    }

    /**
     * Records the current parent (if any) as the parent of [childScope], and sets [childScope] as the new current
     * parent.
     */
    fun enterScope(childScope: Scope) {
        val childDepth = recordChildScope(childScope)
        context.parent = Pair(childScope, childDepth)
    }

    fun enterNodeScopeWithDestructor(element: RsElement) {
        // If node was previously marked as a terminating scope during the recursive visit of its parent node in the
        // AST, then we need to account for the destruction scope representing the scope of the destructors that run
        // immediately after it completes.
    }
}

fun getRegionScopeTree(element: RsElement): ScopeTree {
    val body = element.enclosingBlockOrSelf ?: return ScopeTree()

    val visitor = RegionResolutionVisitor(ScopeTree(), Context())
    visitor.scopeTree.rootBody = body

    if (element is RsImplItem || element is RsTraitItem) {
        visitor.scopeTree.rootParent = element.parent as RsElement
    }

    visitor.visitBlock(body)
    return visitor.scopeTree
}
