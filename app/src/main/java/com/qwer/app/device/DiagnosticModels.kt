package com.qwer.app.device

/**
 * Outcome of a single diagnostic check.
 *
 * [SKIPPED] is a first-class result, not a soft failure: an optional component that is
 * simply absent (for example the separate WDTT Plus app) must never be reported as an
 * error, otherwise the user sees red for something that is not wrong.
 */
enum class CheckStatus {
    OK,
    WARNING,
    ERROR,
    SKIPPED,
}

/**
 * One check result.
 *
 * @param id stable machine identifier, never localised — used by tests and support
 * @param title short human title
 * @param status outcome
 * @param detail what was actually found (facts, not guesses)
 * @param recommendation what the user can do; null when nothing is actionable
 * @param technical extra non-secret data useful in a support ticket
 */
data class DiagnosticCheck(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val recommendation: String? = null,
    val technical: String? = null,
)

/** Aggregate result of a full run. */
data class DiagnosticReport(
    val checks: List<DiagnosticCheck>,
    val generatedAtMillis: Long,
    val appVersion: String,
) {
    val counts: Map<CheckStatus, Int>
        get() = CheckStatus.entries.associateWith { s -> checks.count { it.status == s } }

    /** True when at least one check found a blocking problem. */
    val hasBlockingProblem: Boolean
        get() = checks.any { it.status == CheckStatus.ERROR }

    /** One-line verdict for the UI header. */
    fun summaryLine(): String {
        val c = counts
        val errors = c[CheckStatus.ERROR] ?: 0
        val warnings = c[CheckStatus.WARNING] ?: 0
        return when {
            errors > 0 -> "Проблем: $errors. Требуется внимание"
            warnings > 0 -> "Всё работает, но есть $warnings замечаний"
            else -> "Проблем не обнаружено"
        }
    }
}

/**
 * Raw, non-secret facts collected from the device.
 *
 * Collected once on an IO thread, then classified by [DiagnosticRules] which is pure
 * and therefore fully unit-testable without an emulator.
 *
 * Every field is nullable on purpose: `null` means "could not determine", which must be
 * rendered as SKIPPED rather than guessed into OK or ERROR.
 */
data class DeviceSnapshot(
    val sdkInt: Int?,
    val androidRelease: String?,
    val manufacturer: String?,
    val model: String?,
    val supportedAbis: List<String>,
    val primaryAbi: String?,
    val pageSizeBytes: Long?,
    /** Native libraries that the running APK actually ships, by file name. */
    val bundledNativeLibs: Set<String>,
    /** True when libwg-go.so could be stat'ed in the native library dir. */
    val wgLibPresent: Boolean?,
    /** True when the WDTT client binary is present. Usually absent: that is fine. */
    val wdttClientPresent: Boolean?,
    val vpnPermissionGranted: Boolean?,
    val vpnTransportActive: Boolean?,
    val notificationsEnabled: Boolean?,
    val backgroundRestricted: Boolean?,
    val ignoringBatteryOptimizations: Boolean?,
    val freeMemoryBytes: Long?,
    val totalMemoryBytes: Long?,
    val freeStorageBytes: Long?,
    val networkAvailable: Boolean?,
    val networkTransports: Set<String>,
    val dnsResolved: Boolean?,
    val dnsResolvedHost: String?,
    val filesDirWritable: Boolean?,
)
