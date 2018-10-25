/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId

private const val PLUGIN_ID: String = "org.rust.lang"
private val PLUGIN: IdeaPluginDescriptor = PluginManager.getPlugin(PluginId.getId(PLUGIN_ID))!!

const val LLDB_PP_LOOKUP: String = "lookup"
val LLDB_PP_PATH: String = PLUGIN.path.resolve("prettyPrinters/$LLDB_PP_LOOKUP.py").path
