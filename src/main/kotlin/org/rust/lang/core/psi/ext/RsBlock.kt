/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsStmt

val RsBlock.itemOrStmtChildren: List<RsElement>
    get() = children.mapNotNull { it as? RsItemElement ?: it as? RsStmt }
