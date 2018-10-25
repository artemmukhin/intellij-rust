/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.Label
import com.intellij.ui.layout.panel
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import org.rust.debugger.DataFormatters
import javax.swing.JComponent

class RsDebuggerSettingsConfigurable(
    private val settings: RsDebuggerSettings
) : SearchableConfigurable {

    private val isLLDB = CPPToolchains.getInstance().defaultToolchain?.debuggerKind?.isLLDB() == true

    private val dataFormattersLabel = Label("Data formatters")

    private val dataFormatters = ComboBox<DataFormatters>().apply {
        DataFormatters.values()
            .filter { isLLDB || it != DataFormatters.BUNDLE }
            .sortedBy { it.index }
            .forEach { addItem(it) }
    }

    override fun getId(): String = "Debugger.Rust"
    override fun getDisplayName(): String = DISPLAY_NAME

    override fun createComponent(): JComponent = panel {
        row {
            dataFormattersLabel.labelFor = dataFormatters
            dataFormattersLabel()
            dataFormatters()
        }
    }

    override fun isModified(): Boolean =
        dataFormatters.selectedIndex != settings.dataFormatters.index

    override fun apply() {
        settings.dataFormatters = DataFormatters.fromIndex(dataFormatters.selectedIndex)
    }

    override fun reset() {
        dataFormatters.selectedIndex = settings.dataFormatters.index
    }

    companion object {
        const val DISPLAY_NAME: String = "Rust"
    }
}
