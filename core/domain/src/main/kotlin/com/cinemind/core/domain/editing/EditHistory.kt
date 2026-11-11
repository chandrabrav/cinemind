package com.cinemind.core.domain.editing

/**
 * Undo/redo over immutable editor states. Each successful edit pushes a new state; undo and redo
 * move between them. Keeps at most [limit] undo steps. Not thread-safe: use from one thread.
 */
class EditHistory<T>(initial: T, private val limit: Int = 100) {

    init {
        require(limit > 0) { "limit must be positive" }
    }

    var present: T = initial
        private set

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [next] as the new state. Returns false (and records nothing) if it equals the present. */
    fun push(next: T): Boolean {
        if (next == present) return false
        past.addLast(present)
        if (past.size > limit) past.removeFirst()
        present = next
        future.clear()
        return true
    }

    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(present)
        present = previous
        return true
    }

    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(present)
        present = next
        return true
    }
}
