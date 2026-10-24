package com.qwer.app.core

/**
 * Generation guard for connection operations.
 *
 * THE RACE IT FIXES
 * -----------------
 * A coroutine can be cancelled, but cancellation is cooperative: the engine call inside
 * it may already be running on a native thread and will return *later*. When it returns,
 * the old operation would write `Connected` into state that a newer command already
 * changed — or resurrect a tunnel after the user pressed stop.
 *
 * The guard makes every operation carry a ticket. After each suspend point the operation
 * asks "am I still the current one?" and, if not, it rolls back its own resources instead
 * of publishing a result.
 *
 * Invariants (each covered by a test):
 *  - a new operation invalidates all older tickets;
 *  - a stale operation can never publish state;
 *  - invalidation is idempotent, so repeated stop calls are harmless;
 *  - tickets are monotonically increasing, so no ABA confusion.
 *
 * Pure Kotlin: no Android, no coroutines, fully unit-testable.
 */
class OperationGate {

    private var generation: Long = 0L

    /** Issues a ticket for a new operation and invalidates every older one. */
    @Synchronized
    fun begin(): Long {
        generation += 1
        return generation
    }

    /** Invalidates all outstanding tickets, e.g. on stop or on service destruction. */
    @Synchronized
    fun invalidate() {
        generation += 1
    }

    /** True when [ticket] is still the newest issued operation. */
    @Synchronized
    fun isCurrent(ticket: Long): Boolean = ticket == generation

    /** Current generation, exposed for diagnostics and tests. */
    @Synchronized
    fun current(): Long = generation

    /** Starts a new generation and returns a convenience handle for it. */
    @Synchronized
    fun newHandle(): Handle {
        generation += 1
        return Handle(this, generation)
    }

    /** A ticket plus the gate, so call sites cannot lose the pairing. */
    class Handle internal constructor(
        private val gate: OperationGate,
        val ticket: Long,
    ) {
        /** True while this handle still owns the state. */
        val isCurrent: Boolean get() = gate.isCurrent(ticket)
    }
}
