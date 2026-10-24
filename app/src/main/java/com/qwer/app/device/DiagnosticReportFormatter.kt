package com.qwer.app.device

/**
 * Renders a [DiagnosticReport] as plain text suitable for pasting into a support chat.
 *
 * Safety rules enforced here:
 *  - Only the fields defined in [DiagnosticCheck] are printed; there is no catch-all
 *    dump of the snapshot or the environment.
 *  - Every string passes through [com.qwer.app.diagnostics.Diagnostics.scrub] before it
 *    is written, so a key or token that somehow reached a detail string cannot leak.
 *  - Recommendations are included because a report without them is only half useful.
 */
object DiagnosticReportFormatter {

    fun format(report: DiagnosticReport): String {
        val sb = StringBuilder()
        sb.appendLine("qwer — диагностика устройства")
        sb.appendLine("-".repeat(40))
        sb.appendLine("Приложение: ${report.appVersion}")
        sb.appendLine("Проверок: ${report.checks.size} · ${report.summaryLine()}")
        sb.appendLine()

        // Errors first: a support ticket reader should not scroll to find the problem.
        val ordered = report.checks.sortedBy { it.status.ordinal }
        for (check in ordered) {
            sb.appendLine("${label(check.status)} · ${check.title}")
            sb.appendLine("    ${check.detail}")
            check.recommendation?.let { sb.appendLine("    → $it") }
            check.technical?.let { sb.appendLine("    ($it)") }
        }

        sb.appendLine()
        sb.appendLine("-".repeat(40))
        sb.appendLine("Идентификаторы проверок: " + report.checks.joinToString(", ") { it.id })
        return com.qwer.app.diagnostics.Diagnostics.scrub(sb.toString())
    }

    fun label(status: CheckStatus): String = when (status) {
        CheckStatus.OK -> "[OK]"
        CheckStatus.WARNING -> "[ВНИМАНИЕ]"
        CheckStatus.ERROR -> "[ОШИБКА]"
        CheckStatus.SKIPPED -> "[ПРОПУЩЕНО]"
    }
}
