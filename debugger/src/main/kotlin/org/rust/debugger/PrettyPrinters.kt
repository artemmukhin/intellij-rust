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

enum class DataFormatters(val index: Int, val description: String) {
    NONE(0, "No data formatters"),
    COMPILER(1, "Rust compiler's data formatters"),
    BUNDLE(2, "Bundled data formatters");

    override fun toString(): String = description
    companion object {
        val default: DataFormatters = COMPILER

        fun fromIndex(index: Int): DataFormatters =
            DataFormatters.values().find { it.index == index } ?: default
    }
}
