package com.myvpn.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.myvpn.app.diagnostics.Diagnostics
import com.myvpn.app.diagnostics.Diagnostics.Event

/**
 * Integration point for WDTT Plus.
 *
 * WHY THIS IS A HANDOVER, NOT AN EMBEDDED ENGINE
 * ----------------------------------------------
 * WDTT Plus is GPLv3 and ships as a complete application whose `TunnelService` is
 * declared `android:exported="false"`. That means:
 *   - its service cannot be started or driven by another app, and
 *   - linking its code into this app would force this app to GPLv3.
 *
 * Additionally Android permits only ONE active VpnService per device, so two VPN apps
 * can never run at the same time. Combined with the above, a silent in-process
 * failover WG -> WDTT is impossible with the upstream project as-is.
 *
 * What this adapter does instead: it hands the user over to the installed WDTT Plus
 * app with the profile, using that app's own deep link scheme. The UI states clearly
 * that the switch is a handover, so we never claim a seamless tunnel swap.
 *
 * An in-app WDTT engine (true silent failover) requires vendoring the GPLv3 engine
 * into this APK and adopting GPLv3 for the whole product — an explicit licensing
 * decision that must be made by the product owner, not assumed here.
 */
class WdttPlusAdapter(private val context: Context) {

    data class Availability(val installed: Boolean, val launchIntentAvailable: Boolean)

    fun availability(): Availability {
        val pm = context.packageManager
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(WDTT_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val launchable = installed && context.packageManager
            .getLaunchIntentForPackage(WDTT_PACKAGE) != null
        return Availability(installed, launchable)
    }

    /**
     * Opens WDTT Plus. The profile itself is NOT passed as a query parameter: a VPN
     * config must never travel through an intent URL where it could land in the
     * receiver's logs or the recents screenshot.
     */
    fun handover(): Result<Unit> {
        val avail = availability()
        if (!avail.installed) {
            Diagnostics.event(Event.WDTT_HANDOVER, "result" to "not_installed")
            return Result.failure(WdttNotInstalled())
        }
        val intent = context.packageManager.getLaunchIntentForPackage(WDTT_PACKAGE)
            ?: return Result.failure(WdttNotInstalled())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Diagnostics.event(Event.WDTT_HANDOVER, "result" to "launched")
            Result.success(Unit)
        } catch (e: Exception) {
            Diagnostics.warn(Event.WDTT_HANDOVER, e, "result" to "failed")
            Result.failure(e)
        }
    }

    /**
     * Optional: open WDTT Plus with a profile link it already understands.
     * Only schemes the upstream app declares are used; no invented formats.
     */
    fun handoverWithLink(link: Uri): Result<Unit> {
        val intent = Intent(Intent.ACTION_VIEW, link)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Diagnostics.event(Event.WDTT_HANDOVER, "result" to "link_opened")
            Result.success(Unit)
        } catch (e: Exception) {
            Diagnostics.warn(Event.WDTT_HANDOVER, e, "result" to "link_failed")
            Result.failure(e)
        }
    }

    class WdttNotInstalled : Exception("WDTT Plus не установлен")

    companion object {
        /** Package of the upstream WDTT Plus build. */
        const val WDTT_PACKAGE = "com.wdtt.plus"
    }
}
