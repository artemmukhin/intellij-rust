/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

import org.rust.lang.core.psi.ext.RsElement

/**
 * A "free" region can be interpreted as "some region at least as big as the scope of [element]".
 * When checking a function body, the types of all arguments and so forth that refer to bound region parameters are
 * modified to refer to free region parameters.
 */
data class ReFree(val element: RsElement, val boundRegion: BoundRegion) : Region()
