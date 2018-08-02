/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.infer.RsInferenceContext
import org.rust.lang.core.types.infer.outlives.FreeRegionMap

/**
 * A "free" region can be interpreted as "some region at least as big as the scope of [element]".
 * When checking a function body, the types of all arguments and so forth that refer to bound region parameters are
 * modified to refer to free region parameters.
 */
data class ReFree(val element: RsElement, val boundRegion: BoundRegion) : Region()

/**
 * Combines a [ScopeTree] (which governs relationships between scopes) and a [FreeRegionMap] (which governs
 * relationships between free regions) to yield a complete relation between concrete regions.
 */
data class RegionRelations(
    val inferenceContext: RsInferenceContext,

    // context used to fetch the region maps
    val context: RsElement,

    // region maps for the given context
    val regionScopeTree: ScopeTree,

    // free-region relationships
    val freeRegions: FreeRegionMap
) {

    /** Determines whether one region is a subRegion of another. */
    fun isSubRegionOf(sub: Region, sup: Region): Boolean {
        if (sub == sup) return true
        val result = when {
            sub === ReEmpty && sup === ReStatic -> true
            sub is ReScope && sup is ReScope ->
                regionScopeTree.isSubScopeOf(sub.scope, sup.scope)
            sub is ReScope && sup is ReEarlyBound -> {
                val freeScope = regionScopeTree.getEarlyFreeScope(sup)
                regionScopeTree.isSubScopeOf(sub.scope, freeScope)
            }
            sub is ReScope && sup is ReFree -> {
                val freeScope = regionScopeTree.getFreeScope(sup)
                regionScopeTree.isSubScopeOf(sub.scope, freeScope)
            }
            (sub is ReEarlyBound || sub is ReFree) && (sup is ReEarlyBound || sup is ReFree) ->
                freeRegions.isFreeSubRegionOf(sub, sup)
            else -> false
        }
        return result || isStatic(sup)
    }

    /** Determines whether this free-region is required to be 'static */
    fun isStatic(supRegion: Region): Boolean =
        when (supRegion) {
            is ReStatic -> true
            is ReEarlyBound, is ReFree -> freeRegions.isFreeSubRegionOf(ReStatic, supRegion)
            else -> false
        }

    fun getLeastUpperBoundOfFreeRegions(region1: Region, region2: Region) =
        freeRegions.getLeastUpperBoundOfFreeRegions(region1, region2)
}
