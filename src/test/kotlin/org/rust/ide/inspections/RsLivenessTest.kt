/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

class RsLivenessTest : RsInspectionsTestBase(RsLivenessInspection()) {

    fun `test unused argument empty body`() = checkByText("""
        fn foo(<warning descr="Unused parameter">x</warning>: i32) -> i32 {
            return 42;
        }
    """)

    fun `test simple used argument 1`() = checkByText("""
        fn foo(x: i32) -> i32 {
            return x + 5;
        }
    """)

    fun `test simple used argument 2`() = checkByText("""
        fn foo(cond: bool) -> i32 {
            if cond { 1 } else { 2 }
        }
    """)

    fun `test simple used variable 1`() = checkByText("""
        fn foo() -> i32 {
            let x = 10;
            return x + 5;
        }
    """)

    fun `test simple used variable 2`() = checkByText("""
        fn foo() {
            let x = 10;
            x;
        }
    """)

    fun `test use extending`() = checkByText("""
        struct S { a: i32, b: i32 }

        fn foo(par: i32) {
            let x = S { a: 1, b: 2 };
            if par > 0 {
                x.a;
            }
        }
    """)
}
