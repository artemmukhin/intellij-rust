/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core

import junit.framework.ComparisonFailure
import org.intellij.lang.annotations.Language
import org.rust.lang.RsTestBase
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.ext.descendantsOfType

class RsControlFlowGraphTest : RsTestBase() {

    protected fun testCFG(@Language("Rust") code: String, expectedIndented: String) {
        InlineFile(code)
        val block = myFixture.file.descendantsOfType<RsBlock>().firstOrNull() ?: return
        val cfg = ControlFlowGraph.buildFor(block)
        val expected = expectedIndented.trimIndent()
        val actual = cfg.depthFirstTraversalTrace()
        check(actual == expected) { throw ComparisonFailure("Comparision failed", expected, actual) }
    }

    fun `test empty block`() = testCFG("""
        fn main() {}
    """, """
        Entry
        BLOCK
        Exit
    """
    )

    fun `test straightforward`() = testCFG("""
        fn main() {
            let x = 1;
            let mut y = 2;
            f(x, y);
            y += x;
        }
    """, """
        Entry
        1
        x
        let x = 1;
        2
        mut y
        let mut y = 2;
        f
        x
        y
        f(x, y)
        f(x, y);
        y
        x
        y += x
        y += x;
        BLOCK
        Exit
    """
    )

    fun `test if else with unreachable`() = testCFG("""
        fn main() {
            let x = 1;
            if x > 0 { return; } else { return; }
            let y = 2;
        }
    """, """
        Entry
        1
        x
        let x = 1;
        x
        0
        x > 0
        return
        Exit
        return
    """
    )

    fun `test while`() = testCFG("""
        fn main() {
            let mut x = 1;

            while x < 5 {
                x += 1;
                if x > 3 { return; }
            }
        }
    """, """
        Entry
        1
        mut x
        let mut x = 1;
        Dummy
        x
        5
        x < 5
        WHILE
        BLOCK
        Exit
        x
        1
        x += 1
        x += 1;
        x
        3
        x > 3
        return
        IF
        BLOCK
    """
    )

    fun `test while with unreachable`() = testCFG("""
        fn main() {
            let mut x = 1;

            while x < 5 {
                x += 1;
                if x > 3 { return; } else { x += 10; return; }
                let z = 42;
            }

            let y = 2;
        }
    """, """
        Entry
        1
        mut x
        let mut x = 1;
        Dummy
        x
        5
        x < 5
        WHILE
        WHILE;
        2
        y
        let y = 2;
        BLOCK
        Exit
        x
        1
        x += 1
        x += 1;
        x
        3
        x > 3
        return
        x
        10
        x += 10
        x += 10;
        return
    """
    )

    fun `test match`() = testCFG("""
        enum E { A, B(i32), C }
        fn main() {
            let x = E::A;
            match x {
                E::A => 1,
                E::B(x) => match x { 0 => 2, _ => 3 },
                E::C => 4
            };
            let y = 0;
        }
    """, """
        Entry
        E::A
        x
        let x = E::A;
        x
        E::A
        Dummy
        1
        MATCH
        MATCH;
        0
        y
        let y = 0;
        BLOCK
        Exit
        x
        E::B(x)
        Dummy
        x
        0
        Dummy
        2
        MATCH
        _
        Dummy
        3
        E::C
        Dummy
        4
    """
    )

    fun `test match 1`() = testCFG("""
        enum E { A(i32), B }
        fn main() {
            let x = E::A(1);
            match x {
                E::A(val) => val,
                E::B => return,
            };
            let y = 0;
        }
    """, """
        Entry
        E::A
        1
        E::A(1)
        x
        let x = E::A(1);
        x
        val
        E::A(val)
        Dummy
        val
        MATCH
        MATCH;
        0
        y
        let y = 0;
        BLOCK
        Exit
        E::B
        Dummy
        return
    """
    )

    fun `test try`() = testCFG("""
        fn main() {
            x;
            expr?;
            y;
        }
    """, """
        Entry
        x
        x;
        Dummy
        expr
        expr?
        expr?;
        y
        y;
        BLOCK
        Exit
    """
    )
}
