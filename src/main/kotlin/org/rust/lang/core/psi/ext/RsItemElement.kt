/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsBlockExpr
import org.rust.lang.core.psi.RsConstant
import org.rust.lang.core.psi.RsFunction

interface RsItemElement : RsVisibilityOwner, RsOuterAttributeOwner, RsExpandedElement

val RsItemElement.body: RsBlock?
    get() = when (this) {
        is RsFunction -> block
        is RsConstant -> (expr as? RsBlockExpr)?.block
        else -> null
    }
