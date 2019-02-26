/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.borrowck

import org.rust.ide.inspections.RsBorrowCheckerInspection
import org.rust.ide.inspections.RsInspectionsTestBase

class RsBorrowCheckerLivenessTest : RsInspectionsTestBase(RsBorrowCheckerInspection()) {

    fun `test 123`() = checkByText("""
        fn foo(x: i32) {}
    """, checkWarn = true)

    fun `test move by call`() = checkByText("""
        #[derive(Copy, Clone)]
        struct S { data: i32 }

        fn main() {
            let x = S { data: 42 };
            let mut i = 0;
            while i < 10 {
                if x.data == 10 { f(x); } else {}
                i += 1;
            }
            x;
        }

        fn f(s: S) {}
    """, checkWarn = true)
}
