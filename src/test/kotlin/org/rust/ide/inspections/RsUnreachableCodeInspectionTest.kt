/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

class RsRsUnreachableCodeInspection : RsInspectionsTestBase(RsUnreachableCodeInspection()) {
    fun `test statements after return`() = checkByText("""
        fn main() {
            let x = 1;
            return;
            <weak_warning descr="Unreachable statement">let mut y = 1;</weak_warning>
            <weak_warning descr="Unreachable statement">y += 10;</weak_warning>
        }
    """, checkWeakWarn = true)

    fun `test statements after if-else with return`() = checkByText("""
        fn main() {
            let x = 1;
            if x > 0 { return; } else { return; }
            <weak_warning descr="Unreachable statement">let mut y = 1;</weak_warning>
            <weak_warning descr="Unreachable statement">y += 10;</weak_warning>
        }
    """, checkWeakWarn = true)

    fun `test statements after match with return`() = checkByText("""
        enum Color { White, Black, Red }

        fn main() {
            let x = 1;
            if x > 0 {
                return;
            } else {
                let color: Color;
                match color {
                    Color::White => return,
                    Color::Black => return,
                    Color::Red => return,
                }
            }
            <weak_warning descr="Unreachable statement">let y = 1;</weak_warning>
        }
    """, checkWeakWarn = true)
}
