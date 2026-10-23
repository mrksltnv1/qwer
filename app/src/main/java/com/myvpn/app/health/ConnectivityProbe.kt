package com.myvpn.app.health

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Abstraction over "is the internet reachable right now".
 *
 * Injected so unit tests can simulate outages deterministically, without real sockets
 * and without depending on wall-clock delays.
 */
fun interface ConnectivityProbe {
    /** @return true when the probe target answered within the timeout. */
    fun probe(): Boolean
}

/**
 * TCP-connect probe to well-known endpoints, with a hard TOTAL deadline.
 *
 * Why TCP and not ICMP: raw ICMP needs root, is frequently filtered, and a single
 * unreachable address is not evidence of a broken tunnel. A TCP handshake to port 443
 * exercises the actual data path.
 *
 * Leak protection: the probe does NOT create its own route. It runs inside the app
 * process while the VpnService is up, so it traverses the tunnel like any other app
 * traffic. We never bind to a physical interface to "check" connectivity — that would
 * be exactly the traffic leak we are trying to avoid.
 *
 * DEADLINE MODEL (fixed twice against live device data):
 *  1. `Socket.connect(addr, timeout)` bounds only the connect, NOT the DNS lookup that
 *     happens when the address is built from a hostname. During a real outage that
 *     lookup blocked ~25 s and stretched the probe loop from 2.5 s to 27 s.
 *  2. A per-target deadline is not enough either: with N targets the worst case is
 *     N × deadline, which still breaks the sampling cadence the failover window
 *     depends on. So the whole probe() call shares ONE budget and runs all targets in
 *     parallel; whatever has not answered by then counts as unreachable.
 *
 * Consequence: `probe()` returns within `totalDeadlineMillis` in the worst case, which
 * is what keeps the 2.5 s sampling rhythm and therefore the 12 s failure window honest.
 */
class TcpProbe(
    private val targets: List<Target> = DEFAULT_TARGETS,
    private val connectTimeoutMillis: Int = 2_000,
    /** Hard ceiling for ONE COMPLETE probe across all targets. */
    private val totalDeadlineMillis: Long = 2_000,
) : ConnectivityProbe {

    data class Target(val host: String, val port: Int = 443)

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "vpn-probe").apply { isDaemon = true }
    }

    override fun probe(): Boolean {
        val futures: List<Future<Boolean>> = targets.map { target ->
            executor.submit(Callable { canConnect(target) })
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalDeadlineMillis)
        return try {
            // Poll the futures inside a single shared budget, in priority order.
            for (future in futures) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val ok = try {
                    future.get(remaining, TimeUnit.NANOSECONDS)
                } catch (e: Exception) {
                    false
                }
                if (ok) return true
            }
            false
        } finally {
            // Abandon whatever is still running so slow probes cannot pile up.
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    private fun canConnect(target: Target): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMillis)
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        /**
         * IP literals first: no DNS, so the fast path stays fast even when the
         * resolver is unreachable. Hostnames follow as a second opinion. Kept short
         * because every extra target competes for the same probe budget.
         */
        val DEFAULT_TARGETS = listOf(
            Target("1.1.1.1"),
            Target("8.8.8.8"),
            Target("9.9.9.9"),
            Target("dns.google"),
        )
    }
}
