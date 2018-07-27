/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.psi.ext.RsElement

class MoveData(
    val paths: MutableList<MovePath>,

    /** Cache of loan path to move path index, for easy lookup. */
    val pathMap: MutableMap<LoanPath, MovePathIndex>,

    /** Each move or uninitialized variable gets an entry here. */
    val moves: MutableList<Move>,

    /**
     * Assignments to a variable, like `x = foo`. These are assigned
     * bits for dataflow, since we must track them to ensure that
     * immutable variables are assigned at most once along each path.
     */
    val varAssignments: MutableList<Assignment>,

    /**
     * Assignments to a path, like `x.f = foo`. These are not
     * assigned dataflow bits, but we track them because they still
     * kill move bits.
     */
    val pathAssignments: MutableList<Assignment>,

    /** Assignments to a variable or path, like `x = foo`, but not `x += foo`. */
    val assigneeElements: MutableSet<RsElement>
) {
    fun isEmpty(): Boolean =
        moves.isEmpty() && pathAssignments.isEmpty() && varAssignments.isEmpty()
}

class FlowedMoveData(
    val moveData: MoveData,
    val dataFlowMoves: MoveDataFlow,
    val dataFlowAssign: AssignDataFlow
)

class Move(
    val path: MovePathIndex,
    val element: RsElement,
    val kind: MoveKind,
    /** Next node in linked list of moves from `path` */
    val nextMove: MoveIndex?
)

class MovePath(
    val loanPath: LoanPath,
    val parent: MovePathIndex?,
    val firstMove: MoveIndex?,
    val firstChild: MovePathIndex?,
    val nextSibling: MovePathIndex?
)

data class MoveIndex(val index: Int)
data class MovePathIndex(val index: Int)

enum class MoveKind {
    Declared,   // When declared, variables start out "moved".
    MoveExpr,   // Expression or binding that moves a variable
    MovePat,    // By-move binding
    Captured    // Closure creation that moves a value
}

object MoveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no loans in scope by default
}

object AssignDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no assignments in scope by default
}

typealias MoveDataFlow = DataFlowContext<MoveDataFlowOperator>
typealias AssignDataFlow = DataFlowContext<AssignDataFlowOperator>

class Assignment(
    // path being assigned
    val path: MovePathIndex,

    // where assignment occurs
    val element: RsElement,

    // element for place expression on lhs of assignment
    val assignee: RsElement
)
