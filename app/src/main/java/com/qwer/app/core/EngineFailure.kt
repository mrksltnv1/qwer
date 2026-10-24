package com.qwer.app.core

/**
 * Stage of the connection process where a failure happened.
 *
 * These are the stages that actually exist in this app (see ConnectionManager.connectInternal
 * and connectWdttLocked). Nothing is invented: each value maps to a real step that can throw.
 */
enum class ConnectionStage(val title: String) {
    /** Config text is missing, unparsable, or rejected by the importer. */
    PROFILE("Проверка профиля"),
    /** Engine preflight: sanitising the config, building the wire config. */
    ENGINE_PREP("Подготовка движка"),
    /** Android VPN consent is not granted. */
    PERMISSION("Разрешение VPN"),
    /** Starting the VpnService / engine backend. */
    VPN_SERVICE("Запуск VPN-сервиса"),
    /** Creating the tun interface. */
    TUNNEL_INIT("Создание туннеля"),
    /** Handshake and activation with the remote peer. */
    CONNECT("Установка соединения"),
    /** Resolving the endpoint host. */
    DNS("DNS"),
    /** Tunnel is up but traffic does not flow / it stopped unexpectedly. */
    RUNTIME("Работа соединения"),
    /** Tearing the tunnel down. */
    STOP("Остановка"),
    /** Could not classify; still shown, never hidden. */
    UNKNOWN("Неизвестный этап"),
}

/**
 * A user-facing connection failure.
 *
 * @param code stable machine identifier (engine reason name, or our own) — never localised
 * @param stage where it happened
 * @param title short headline for the UI
 * @param description what actually happened, in plain words
 * @param recommendation what the user can do; null when there is nothing actionable
 * @param technical safe diagnostic detail for the log/ticket (no secrets)
 */
data class EngineFailure(
    val code: String,
    val stage: ConnectionStage,
    val title: String,
    val description: String,
    val recommendation: String?,
    val technical: String?,
) {
    /** One string for ConnectionState.Failed.detail: headline plus, if present, advice. */
    fun userMessage(): String = if (recommendation != null) {
        "$title. $description $recommendation"
    } else {
        "$title. $description"
    }
}

/**
 * Maps engine errors to [EngineFailure].
 *
 * Pure Kotlin by design: the AAR's `BackendException.Reason` is reduced to its name
 * string before reaching this object, so every branch is unit-testable on a plain JVM.
 *
 * Coverage rule: ALL eight values of `BackendException.Reason` are handled explicitly.
 * An unknown code is never dropped — it is surfaced with its raw code so support can act
 * on it instead of seeing a generic "connection failed".
 */
object EngineErrorMapper {

    /** All reasons defined in org.amnezia.awg.backend.BackendException.Reason. */
    val KNOWN_REASON_CODES = setOf(
        "UNKNOWN_KERNEL_MODULE_NAME",
        "AWG_QUICK_CONFIG_ERROR_CODE",
        "TUNNEL_MISSING_CONFIG",
        "VPN_NOT_AUTHORIZED",
        "UNABLE_TO_START_VPN",
        "TUN_CREATION_ERROR",
        "GO_ACTIVATION_ERROR_CODE",
        "DNS_RESOLUTION_FAILURE",
    )

    fun fromReasonCode(
        reasonCode: String?,
        rawMessage: String? = null,
        formatArg: String? = null,
    ): EngineFailure {
        val code = reasonCode?.trim()?.takeIf { it.isNotEmpty() }
        return when (code) {
            "VPN_NOT_AUTHORIZED" -> EngineFailure(
                code = code,
                stage = ConnectionStage.PERMISSION,
                title = "Нет разрешения на VPN",
                description = "Android не разрешил приложению создать VPN-подключение.",
                recommendation = "Подключитесь снова и подтвердите запрос системы.",
                technical = null,
            )
            "TUNNEL_MISSING_CONFIG" -> EngineFailure(
                code = code,
                stage = ConnectionStage.PROFILE,
                title = "Конфигурация не найдена",
                description = "Сохранённый конфиг профиля недоступен в защищённом хранилище.",
                recommendation = "Удалите профиль и импортируйте конфигурацию заново.",
                technical = null,
            )
            "UNABLE_TO_START_VPN" -> EngineFailure(
                code = code,
                stage = ConnectionStage.VPN_SERVICE,
                title = "Не удалось запустить VPN-сервис",
                description = "Система не дала поднять службу VPN.",
                recommendation = "Проверьте, не занят ли VPN другим приложением, и повторите.",
                technical = null,
            )
            "TUN_CREATION_ERROR" -> EngineFailure(
                code = code,
                stage = ConnectionStage.TUNNEL_INIT,
                title = "Не удалось создать туннель",
                description = "Система отказала в создании сетевого интерфейса.",
                recommendation = "Отключите другие VPN-приложения и попробуйте снова.",
                technical = null,
            )
            "DNS_RESOLUTION_FAILURE" -> EngineFailure(
                code = code,
                stage = ConnectionStage.DNS,
                title = "Не удалось разрешить адрес сервера",
                description = formatArg?.let { "Адрес $it не разрешается в IP." }
                    ?: "Имя сервера из конфигурации не разрешается в IP-адрес.",
                recommendation = "Проверьте адрес в конфигурации и работу DNS в текущей сети.",
                technical = formatArg,
            )
            "GO_ACTIVATION_ERROR_CODE" -> EngineFailure(
                code = code,
                stage = ConnectionStage.CONNECT,
                title = "Движок отказал при запуске",
                description = formatArg?.let { "Туннель не активировался, код движка $it." }
                    ?: "Туннель не активировался.",
                recommendation = "Проверьте конфигурацию и повторите подключение.",
                technical = formatArg?.let { "GO_ACTIVATION_ERROR_CODE=$it" },
            )
            "AWG_QUICK_CONFIG_ERROR_CODE" -> EngineFailure(
                code = code,
                stage = ConnectionStage.ENGINE_PREP,
                title = "Движок не принял конфигурацию",
                description = "AmneziaWG-совместимый конфиг отклонён при разборе.",
                recommendation = "Проверьте параметры обфускации (Jc, S1–S4, H1–H4) в конфигурации.",
                technical = formatArg?.let { "AWG_QUICK_CONFIG_ERROR_CODE=$it" },
            )
            "UNKNOWN_KERNEL_MODULE_NAME" -> EngineFailure(
                code = code,
                stage = ConnectionStage.ENGINE_PREP,
                title = "Ядро не поддерживает AmneziaWG",
                description = "Движок не смог подобрать модуль ядра для этого устройства.",
                recommendation = "Используйте обычный WireGuard-профиль.",
                technical = null,
            )
            else -> unknown(code, rawMessage)
        }
    }

    /**
     * Fallback for anything not in the enum: a message that merely mentions a known
     * failure is still classified, and the raw text is preserved for diagnostics.
     */
    private fun unknown(code: String?, rawMessage: String?): EngineFailure {
        val message = rawMessage?.trim()?.takeIf { it.isNotEmpty() }

        // Some engine paths throw plain messages instead of BackendException.
        if (message != null) {
            val byText = when {
                message.contains("BadConfig", ignoreCase = true) ||
                    message.contains("Bad configuration", ignoreCase = true) -> EngineFailure(
                    code = "BAD_CONFIG",
                    stage = ConnectionStage.ENGINE_PREP,
                    title = "Движок отклонил конфигурацию",
                    description = "Файл конфигурации не соответствует формату.",
                    recommendation = "Проверьте, что это корректный конфиг WireGuard или AmneziaWG.",
                    technical = message.take(160),
                )
                message.contains("VPN_NOT_AUTHORIZED", ignoreCase = true) ->
                    fromReasonCode("VPN_NOT_AUTHORIZED", message)
                message.contains("UNABLE_TO_START_VPN", ignoreCase = true) ->
                    fromReasonCode("UNABLE_TO_START_VPN", message)
                message.contains("TUN_CREATION_ERROR", ignoreCase = true) ->
                    fromReasonCode("TUN_CREATION_ERROR", message)
                message.contains("DNS_RESOLUTION_FAILURE", ignoreCase = true) ->
                    fromReasonCode("DNS_RESOLUTION_FAILURE", message)
                else -> null
            }
            if (byText != null) return byText
        }

        // Unknown stays unknown — but with its code and text intact, never swallowed.
        return EngineFailure(
            code = code ?: "UNKNOWN",
            stage = ConnectionStage.UNKNOWN,
            title = "Не удалось подключиться",
            description = "Движок сообщил об ошибке, которую не удалось распознать.",
            recommendation = "Откройте «Диагностику» и приложите отчёт к обращению.",
            technical = buildString {
                if (code != null) append("code=").append(code)
                if (message != null) {
                    if (isNotEmpty()) append(" · ")
                    append(message.take(160))
                }
            }.ifEmpty { null },
        )
    }
}
