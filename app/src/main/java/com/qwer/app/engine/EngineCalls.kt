package com.qwer.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounded wrappers around the engine's blocking calls.
 *
 * WHY THIS EXISTS — the 2026-10-31 device run
 * -------------------------------------------
 * `GoBackend.down()` on a tunnel whose endpoint never answered did not return. The
 * connect/stop path holds [com.qwer.app.core.ConnectionManager]'s single `mutex` while it
 * talks to the engine, so a hung teardown froze the ENTIRE state machine:
 *
 *   16:11:04.212  rebuild_scheduled attempt=1
 *   16:11:06.706  rebuild             attempt=1   <- entered stopForRestart()
 *   (nothing for minutes: no attempt=2, no probe, no state change)
 *
 * From the outside this is exactly the reported "подключается 2-3 раза, а потом всё":
 * the UI keeps its last state, the retry ladder never advances because its first step
 * never finishes, and only a manual disconnect clears it.
 *
 * WHY NOT `withTimeout`
 * ---------------------
 * `withTimeout` cancels the COROUTINE, but a blocking/native call does not check for
 * cancellation: the child job never completes, so `withTimeoutOrNull` waits for it just
 * as long as the call itself. The first fix attempt proved this on the device — the log
 * still stopped at `rebuild attempt=1`. The call therefore has to leave the coroutine
 * world entirely: it runs on its own thread and the coroutine waits on a `Future` with a
 * real deadline.
 *
 * A leaked native thread is far less harmful than a permanently locked state machine.
 */
object EngineCalls {

    /**
     * Ceiling for a teardown. `down()` normally returns in milliseconds; anything past
     * this means the backend is stuck, not busy.
     */
    const val DOWN_TIMEOUT_MS = 8_000L

    /** Ceiling for a start. A WDTT config can take seconds to hand over to wg-go. */
    const val UP_TIMEOUT_MS = 25_000L

    /**
     * Daemon threads on purpose: a wedged engine thread must never keep the process
     * alive on its own, and it must not block a clean shutdown.
     */
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "qwer-engine-call").apply { isDaemon = true }
    }

    /** Outcome of a bounded engine call. */
    data class Outcome<T>(
        /** False when the call had to be abandoned because it exceeded its budget. */
        val finished: Boolean,
        val value: T? = null,
        /** Non-null when the call itself failed (not when it timed out). */
        val error: Throwable? = null,
    )

    /**
     * Runs a blocking engine call with a real deadline.
     *
     * The call is executed on [executor]; the coroutine only ever waits on the returned
     * `Future`, so a hung backend can no longer hold a caller's mutex.
     */
    suspend fun <T> bounded(
        timeoutMillis: Long,
        block: () -> T,
    ): Outcome<T> = withContext(Dispatchers.IO) {
        val future: Future<T> = executor.submit<T> { block() }
        try {
            Outcome(finished = true, value = future.get(timeoutMillis, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            // Best effort: interrupt a cooperative backend. A native one will keep its
            // thread, which is exactly why the caller drops the engine object instead of
            // reusing it.
            future.cancel(true)
            Outcome(finished = false)
        } catch (c: CancellationException) {
            future.cancel(true)
            throw c
        } catch (e: InterruptedException) {
            future.cancel(true)
            Outcome(finished = false)
        } catch (e: Throwable) {
            // ExecutionException wraps whatever the engine threw; unwrap it so callers
            // keep classifying by the real exception type.
            Outcome(finished = true, error = e.cause ?: e)
        }
    }
}
