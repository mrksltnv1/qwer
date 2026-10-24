package com.qwer.app.device

import android.content.Context
import com.qwer.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the whole diagnostic: collect facts, classify them, build a report.
 *
 * Failures are contained per stage: if the probe throws, the report still renders with
 * whatever was collected, and a single failed check never aborts the rest.
 */
class DiagnosticEngine(private val context: Context) {

    private val probe = DeviceProbe(context)

    suspend fun run(): DiagnosticReport = withContext(Dispatchers.IO) {
        val snapshot = runCatching { probe.collect() }
            .getOrElse { emptySnapshot() }

        val checks = runCatching { DiagnosticRules.evaluate(snapshot) }
            .getOrElse {
                listOf(
                    DiagnosticCheck(
                        id = "internal_error",
                        title = "Внутренняя ошибка диагностики",
                        status = CheckStatus.SKIPPED,
                        detail = "Проверки не удалось выполнить полностью",
                        recommendation = "Скопируйте отчёт и приложите его к обращению",
                    )
                )
            }

        DiagnosticReport(
            checks = checks,
            generatedAtMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    /** Snapshot with every field unknown: rules then render everything as SKIPPED. */
    private fun emptySnapshot() = DeviceSnapshot(
        sdkInt = null,
        androidRelease = null,
        manufacturer = null,
        model = null,
        supportedAbis = emptyList(),
        primaryAbi = null,
        pageSizeBytes = null,
        bundledNativeLibs = emptySet(),
        wgLibPresent = null,
        wdttClientPresent = null,
        vpnPermissionGranted = null,
        vpnTransportActive = null,
        notificationsEnabled = null,
        backgroundRestricted = null,
        ignoringBatteryOptimizations = null,
        freeMemoryBytes = null,
        totalMemoryBytes = null,
        freeStorageBytes = null,
        networkAvailable = null,
        networkTransports = emptySet(),
        dnsResolved = null,
        dnsResolvedHost = null,
        filesDirWritable = null,
    )
}
