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
        struct S { a: i32 }

        fn f(s: &mut S) { s.a += 1; }

        fn main() {
            let <warning descr="Mut is unused">mut x</warning> = 1;
            let mut y = 2;
            let mut z = S { a: 3 };
            let mut i = 0;

            while i < 10 {
                if x > 0 {
                    y += x;
                }
                i += 1;
            }
            f(&mut z);
        }
    """)

    fun `test unused mut 3`() = checkWarnings("""
        struct S;
        impl S {
            fn test(&mut self) {}
        }
        fn main() {
            let mut x = S;
            x.test();
        }
    """)

}
