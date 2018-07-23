/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

import org.rust.lang.core.psi.ext.RsElement

class Scope private constructor(val element: RsElement) {
    companion object {
        fun createNode(element: RsElement): Scope = Scope(element)
    }
}

typealias ScopeDepth = Int
typealias ScopeInfo = Pair<Scope, ScopeDepth>

class ScopeTree(
    private val parentMap: MutableMap<Scope, ScopeInfo> = mutableMapOf()
) {
    /** Returns the narrowest scope that encloses [scope], if any */
    fun getEnclosingScope(scope: Scope): Scope? = parentMap[scope]?.first
}

fun getRegionScopeTree(element: RsElement): ScopeTree = ScopeTree()
