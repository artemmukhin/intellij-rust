/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.regions

/**
 * Empty lifetime is for data that is never accessed. Bottom in the region lattice.
 * We treat [ReEmpty] somewhat specially; at least right now, we do not generate instances of it during the GLB
 * computations, but rather generate an error instead. This is to improve error messages.
 * The only way to get an instance of [ReEmpty] is to have a region variable with no constraints.
 */
object ReEmpty : Region()
