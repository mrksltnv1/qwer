package com.qwer.app.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Lists the apps a user can actually launch, for the per-app routing picker.
 *
 * WHY NOT getInstalledApplications()
 * ----------------------------------
 * That returns every package on the device, including dozens of system components with
 * no launcher icon (`com.android.providers.media`, countless `*.overlay` entries). A list
 * where the user cannot recognise a single row is useless. Querying the launcher intent
 * gives exactly the apps that appear in the drawer.
 *
 * The app's own package is filtered out: it is always excluded from or included in the
 * tunnel by [com.qwer.app.core.AppRouting] itself, so offering it as a choice would let
 * the user create a broken configuration.
 */
object InstalledApps {

    data class Entry(
        val packageName: String,
        val label: String,
    )

    fun launchable(context: Context, ownPackage: String = context.packageName): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // QUERY_ALL_PACKAGES is not requested (Play policy), so on API 30+ the result is
        // limited by <queries>. Launchable apps are visible through the launcher intent
        // filter without extra permissions.
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != ownPackage }
            .distinctBy { it.packageName }
            .map { info ->
                Entry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Display label for a package that may no longer be installed. */
    fun labelFor(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
