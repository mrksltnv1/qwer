package com.qwer.app.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

/**
 * Collects a [DeviceSnapshot] from Android.
 *
 * Rules followed here:
 *  - Nothing runs on the main thread: the whole collection happens on IO.
 *  - No shell commands, no root, no destructive network traffic. The only network
 *    activity is a single DNS lookup of a well-known host, bounded in time.
 *  - Anything that cannot be determined stays `null`, which the rules render as
 *    SKIPPED. Guessing would produce false alarms.
 *  - No secret ever enters the snapshot: it holds ABIs, sizes and booleans only.
 */
class DeviceProbe(private val context: Context) {

    /** Linux _SC_PAGESIZE: fixed by the platform ABI, not a magic number. */
    private val SC_PAGESIZE_LINUX = 39

    /** Host used for the DNS check. Public, stable, no user data involved. */
    private val dnsProbeHost = "one.one.one.one"

    suspend fun collect(): DeviceSnapshot = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bundled = runCatching {
            File(nativeDir).listFiles()
                ?.filter { it.name.endsWith(".so") }
                ?.map { it.name }
                ?.toSet()
                ?: emptySet()
        }.getOrElse { emptySet() }

        val wgLib = runCatching { File(nativeDir, "libwg-go.so").isFile }.getOrNull()
        val wdttLib = runCatching { File(nativeDir, "libclient.so").isFile }.getOrNull()

        val (netAvailable, transports) = networkState()
        val dnsOk = runCatching {
            // Bounded by the resolver; a failure is a legitimate WARNING, not a crash.
            InetAddress.getByName(dnsProbeHost) != null
        }.getOrNull()

        val snapshot = DeviceSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull(),
            pageSizeBytes = pageSizeBytes(),
            bundledNativeLibs = bundled,
            wgLibPresent = wgLib,
            wdttClientPresent = wdttLib,
            vpnPermissionGranted = vpnPermissionGranted(),
            vpnTransportActive = transports.contains("vpn"),
            notificationsEnabled = notificationsEnabled(),
            backgroundRestricted = backgroundRestricted(),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            freeMemoryBytes = freeMemory(),
            totalMemoryBytes = totalMemory(),
            freeStorageBytes = freeStorage(),
            networkAvailable = netAvailable,
            networkTransports = transports - "vpn",
            dnsResolved = dnsOk,
            dnsResolvedHost = dnsProbeHost,
            filesDirWritable = filesDirWritable(),
        )

        Diagnostics.event(
            Event.DEVICE_DIAGNOSTICS,
            "sdk" to snapshot.sdkInt,
            "abi" to snapshot.primaryAbi,
            "wg_lib" to snapshot.wgLibPresent,
            "pages" to snapshot.pageSizeBytes,
        )
        snapshot
    }

    // ---------------------------------------------------------------- probes

    /**
     * Page size reported by the platform.
     *
     * `Os.sysconf(SC_PAGESIZE)` is the accurate value; `ActivityManager.memoryClass` is
     * unrelated. On API < 30 there is no public API, so it stays null (SKIPPED).
     */
    private fun pageSizeBytes(): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // android.system.OsConstants does not expose SC_PAGESIZE, so the Linux
            // _SC_PAGESIZE constant is used explicitly. Value 39 is fixed by the Linux
            // ABI and is identical on arm64, armv7 and x86_64.
            android.system.Os.sysconf(SC_PAGESIZE_LINUX)
        } else {
            null
        }
    }.getOrNull()

    private fun vpnPermissionGranted(): Boolean? = runCatching {
        // prepare() returns null when consent already exists. It does NOT trigger a
        // dialog on its own, so calling it here is side-effect free.
        android.net.VpnService.prepare(context) == null
    }.getOrNull()

    private fun notificationsEnabled(): Boolean? = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrNull()

    private fun backgroundRestricted(): Boolean? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            am.isBackgroundRestricted
        } else {
            null
        }
    }.getOrNull()

    private fun ignoringBatteryOptimizations(): Boolean? = runCatching {
        val pm = context.getSystemService(PowerManager::class.java) ?: return@runCatching null
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    private fun freeMemory(): Long? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem
    }.getOrNull()

    private fun totalMemory(): Long? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    }.getOrNull()

    private fun freeStorage(): Long? = runCatching {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()

    private fun filesDirWritable(): Boolean? = runCatching {
        val probe = File(context.filesDir, ".diag_probe")
        val created = probe.createNewFile() || probe.exists()
        probe.delete()
        created
    }.getOrNull()

    /** @return network available + set of transport labels, including "vpn". */
    private fun networkState(): Pair<Boolean?, Set<String>> {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching (null as Boolean?) to emptySet<String>()
            val network = cm.activeNetwork ?: return@runCatching false to emptySet<String>()
            val caps = cm.getNetworkCapabilities(network)
                ?: return@runCatching false to emptySet<String>()

            val transports = buildSet {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("мобильная")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            }
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            hasInternet to transports
        }.getOrElse { (null as Boolean?) to emptySet<String>() }
    }
}
