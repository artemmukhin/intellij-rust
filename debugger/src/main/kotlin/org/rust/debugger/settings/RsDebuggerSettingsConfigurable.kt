/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.layout.panel
import org.rust.openapiext.CheckboxDelegate
import javax.swing.JComponent

class RsDebuggerSettingsConfigurable(
    private val settings: RsDebuggerSettings
) : SearchableConfigurable {

    private val isRendersEnabledCheckbox: JBCheckBox = JBCheckBox("Enable Rust library renders")
    private var isRendersEnabled: Boolean by CheckboxDelegate(isRendersEnabledCheckbox)

    private val isBundledPrintersEnabledCheckbox: JBCheckBox = JBCheckBox("Enable bundled pretty-printers")
    private var isBundledPrintersEnabled: Boolean by CheckboxDelegate(isBundledPrintersEnabledCheckbox)

    override fun getId(): String = "Debugger.Rust"
    override fun getDisplayName(): String = DISPLAY_NAME

    override fun createComponent(): JComponent = panel {
        row { isRendersEnabledCheckbox() }
        row { isBundledPrintersEnabledCheckbox() }
    }

    override fun isModified(): Boolean =
        isRendersEnabled != settings.isRendersEnabled || isBundledPrintersEnabled != settings.isBundledPrintersEnabled

    override fun apply() {
        settings.isRendersEnabled = isRendersEnabled
        settings.isBundledPrintersEnabled = isBundledPrintersEnabled
    }

    override fun reset() {
        isRendersEnabled = settings.isRendersEnabled
        isBundledPrintersEnabled = settings.isBundledPrintersEnabled
    }

    companion object {
        const val DISPLAY_NAME: String = "Rust"
    }
}
