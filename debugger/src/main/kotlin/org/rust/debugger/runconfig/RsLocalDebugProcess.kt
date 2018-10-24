/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.runconfig

import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.cidr.execution.RunParameters
import com.jetbrains.cidr.execution.debugger.CidrLocalDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerCommandException
import com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriver
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver
import org.rust.debugger.LLDB_PP
import org.rust.debugger.LLDB_PP_PATH
import java.nio.file.InvalidPathException

class RsLocalDebugProcess(
    parameters: RunParameters,
    debugSession: XDebugSession,
    consoleBuilder: TextConsoleBuilder
) : CidrLocalDebugProcess(parameters, debugSession, consoleBuilder) {
    fun loadRustcPrettyPrinters(sysroot: String) {
        postCommand { driver ->
            when (driver) {
                is LLDBDriver -> driver.loadRustcPrettyPrinter(currentThreadId, currentFrameIndex, sysroot)
                is GDBDriver -> driver.loadRustcPrettyPrinter(currentThreadId, currentFrameIndex, sysroot)
            }
        }
    }

    fun loadBundledPrettyPrinters() {
        postCommand { driver ->
            when (driver) {
                is LLDBDriver -> driver.loadBundledPrettyPrinter(currentThreadId, currentFrameIndex)
                is GDBDriver -> {
                    // TODO
                }
            }
        }
    }

    private fun LLDBDriver.loadBundledPrettyPrinter(threadId: Long, frameIndex: Int) {
        try {
            executeConsoleCommand(threadId, frameIndex, """command script import "$LLDB_PP_PATH" """)
            executeConsoleCommand(threadId, frameIndex, """type synthetic add -l $LLDB_PP.StdVecProvider -x "^(alloc::([a-zA-Z]+::)+)Vec<.+>$" --category Rust""")
            executeConsoleCommand(threadId, frameIndex, """type summary add -F $LLDB_PP.SizeSummaryProvider -e -x "^(alloc::([a-zA-Z]+::)+)Vec<.+>$" --category Rust""")
        } catch (e: DebuggerCommandException) {
            printlnToConsole(e.message)
            LOG.warn(e)
        } catch (e: InvalidPathException) {
            LOG.warn(e)
        }
    }

    private fun LLDBDriver.loadRustcPrettyPrinter(threadId: Long, frameIndex: Int, sysroot: String) {
        val rustcPrinterPath = "$sysroot/lib/rustlib/etc/lldb_rust_formatters.py".systemDependentAndEscaped()
        try {
            executeConsoleCommand(threadId, frameIndex, """command script import "$rustcPrinterPath" """)
            executeConsoleCommand(threadId, frameIndex, """type summary add --no-value --python-function lldb_rust_formatters.print_val -x ".*" --category Rust""")
            executeConsoleCommand(threadId, frameIndex, """type category enable Rust""")
        } catch (e: DebuggerCommandException) {
            printlnToConsole(e.message)
            LOG.warn(e)
        }
    }

    private fun GDBDriver.loadRustcPrettyPrinter(threadId: Long, frameIndex: Int, sysroot: String) {
        val path = "$sysroot/lib/rustlib/etc".systemDependentAndEscaped()
        // Avoid multiline Python scripts due to https://youtrack.jetbrains.com/issue/CPP-9090
        val command = """python """ +
            """sys.path.insert(0, "$path"); """ +
            """import gdb_rust_pretty_printing; """ +
            """gdb_rust_pretty_printing.register_printers(gdb); """
        try {
            executeConsoleCommand(threadId, frameIndex, command)
        } catch (e: DebuggerCommandException) {
            printlnToConsole(e.message)
            LOG.warn(e)
        }
    }

    private fun String.systemDependentAndEscaped(): String =
        StringUtil.escapeStringCharacters(FileUtil.toSystemDependentName(this))

    companion object {
        private val LOG: Logger = Logger.getInstance(RsLocalDebugProcess::class.java)
    }
}
