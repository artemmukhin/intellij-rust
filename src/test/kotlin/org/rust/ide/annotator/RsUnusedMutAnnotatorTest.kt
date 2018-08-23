/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.testFramework.LightProjectDescriptor

class RsUnusedMutAnnotatorTest : RsAnnotationTestBase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = WithStdlibRustProjectDescriptor

    fun `test unused mut 1`() = checkWarnings("""
        fn main() {
            let <warning descr="Mut is unused">mut x</warning> = 1;
            let y = 2;
        }
    """)

    fun `test unused mut 2`() = checkWarnings("""
        fn main() {
            let <warning descr="Mut is unused">mut x</warning> = 1;
            let mut y = 2;
            let mut i = 0;
            while i < 10 {
                if x > 0 {
                    y += x;
                }
                i += 1;
            }
        }
    """)

}
