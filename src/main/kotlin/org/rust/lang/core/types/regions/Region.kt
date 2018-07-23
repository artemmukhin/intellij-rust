/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.Kind
import org.rust.lang.core.types.TypeFlags
import org.rust.lang.core.types.infer.TypeFoldable
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.infer.TypeVisitor

/**
 * We use the terms `region` and `lifetime` interchangeably.
 * The name `Region` inspired by the Rust compiler.
 */
abstract class Region : Kind, TypeFoldable<Region> {
    override val flags: TypeFlags = 0

    override fun superFoldWith(folder: TypeFolder): Region = folder.foldRegion(this)
    override fun superVisitWith(visitor: TypeVisitor): Boolean = visitor.visitRegion(this)
}

sealed class BoundRegion {
    /** An anonymous region parameter for a given fn (&T) */
    data class Anon(val i: Int) : BoundRegion()

    /** Named region parameters for functions (a in &'a T) */
    data class Named(val element: RsElement) : BoundRegion()

    /** Fresh bound identifiers created during GLB computations */
    data class Fresh(val i: Int) : BoundRegion()

    /** Anonymous region for the implicit env pointer parameter to a closure */
    object Env : BoundRegion()
}

/**
 * A De Bruijn index is a standard means of representing regions (and perhaps later types) in a higher-ranked setting.
 * http://en.wikipedia.org/wiki/De_Bruijn_index
 */
data class DeBruijnIndex(val depth: Int, val bound: BoundRegion)
