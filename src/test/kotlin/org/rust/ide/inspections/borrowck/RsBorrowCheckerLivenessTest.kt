/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.borrowck

import org.rust.ProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.ide.inspections.RsBorrowCheckerInspection
import org.rust.ide.inspections.RsInspectionsTestBase

class RsBorrowCheckerLivenessTest : RsInspectionsTestBase(RsBorrowCheckerInspection()) {

    fun `test unused arguments empty body`() = checkByText("""
        fn foo(<warning descr="Unused parameter">x</warning>: i32, <warning descr="Unused parameter">y</warning>: String) {}
    """, checkWarn = true)

    fun `test simple used argument`() = checkByText("""
        fn foo(x: i32) -> i32 {
            return x + 5;
        }
    """)

    fun `test simple used variable`() = checkByText("""
        fn foo() -> i32 {
            let x = 10;
            return x + 5;
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test use extending`() = checkByText("""
        #[derive(Copy, Clone)]
        struct S { data: i32 }

        fn main() {
            let x = S { data: 42 };
            let mut i = 0;
            while i < 10 {
                i += 1;
            }
            x;
        }
 """, checkWarn = true)
}
