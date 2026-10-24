package com.qwer.app.device

/**
 * Turns a [DeviceSnapshot] into user-facing checks.
 *
 * Pure: no Android imports, no IO, no clock. Everything that can fail is expressed as a
 * nullable field in the snapshot, and this object decides whether the user should see
 * OK / WARNING / ERROR / SKIPPED. That split is what makes the whole diagnostic screen
 * testable on a plain JVM.
 */
object DiagnosticRules {

    /** First page size that Android 15+ requires native libraries to be aligned to. */
    const val SIXTEEN_KB = 16L * 1024L

    /** Below this, the engine may be killed while starting a tunnel. */
    private const val LOW_MEMORY_BYTES = 96L * 1024 * 1024

    /** Below this free space, config writes and logs become unreliable. */
    private const val LOW_STORAGE_BYTES = 64L * 1024 * 1024

    /** ABIs the shipped APK actually contains. Order does not matter. */
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun evaluate(s: DeviceSnapshot): List<DiagnosticCheck> = listOf(
        androidVersion(s),
        appBuild(s),
        abi(s),
        nativeLibs(s),
        pageSize(s),
        vpnApi(s),
        vpnPermission(s),
        conflictingVpn(s),
        network(s),
        dns(s),
        notifications(s),
        backgroundRestrictions(s),
        storage(s),
        memory(s),
        wdttEngine(s),
    )

    // ---------------------------------------------------------------- checks

    private fun androidVersion(s: DeviceSnapshot): DiagnosticCheck {
        val sdk = s.sdkInt
        if (sdk == null) {
            return DiagnosticCheck(
                id = "android_version",
                title = "Версия Android",
                status = CheckStatus.SKIPPED,
                detail = "Не удалось определить версию системы",
            )
        }
        val release = s.androidRelease ?: "?"
        val detail = "Android $release (API $sdk)"
        return when {
            sdk < 24 -> DiagnosticCheck(
                id = "android_version",
                title = "Версия Android",
                status = CheckStatus.ERROR,
                detail = detail,
                recommendation = "Приложению нужен Android 7.0 (API 24) или новее",
                technical = "minSdk=24",
            )
            sdk < 26 -> DiagnosticCheck(
                id = "android_version",
                title = "Версия Android",
                status = CheckStatus.WARNING,
                detail = detail,
                recommendation = "На старых версиях Android фоновая работа ограничена сильнее",
                technical = "minSdk=24",
            )
            sdk >= 34 -> DiagnosticCheck(
                id = "android_version",
                title = "Версия Android",
                status = CheckStatus.OK,
                detail = "$detail · учитываются ограничения фоновой работы",
            )
            else -> DiagnosticCheck(
                id = "android_version",
                title = "Версия Android",
                status = CheckStatus.OK,
                detail = detail,
            )
        }
    }

    private fun appBuild(s: DeviceSnapshot): DiagnosticCheck = DiagnosticCheck(
        id = "app_build",
        title = "Версия приложения",
        status = CheckStatus.OK,
        detail = s.model?.let { "${s.manufacturer ?: ""} $it".trim() } ?: "Модель не определена",
        technical = "ABI: ${s.primaryAbi ?: "?"} · поддерживаемые: ${s.supportedAbis.joinToString(", ").ifEmpty { "?" }}",
    )

    private fun abi(s: DeviceSnapshot): DiagnosticCheck {
        val abi = s.primaryAbi
        if (abi == null) {
            return DiagnosticCheck(
                id = "abi",
                title = "Архитектура процессора",
                status = CheckStatus.SKIPPED,
                detail = "Не удалось определить ABI",
                technical = s.supportedAbis.joinToString(", ").ifEmpty { null },
            )
        }
        return if (abi in SUPPORTED_ABIS) {
            DiagnosticCheck(
                id = "abi",
                title = "Архитектура процессора",
                status = CheckStatus.OK,
                detail = abi,
                technical = "Поддерживаемые устройством: ${s.supportedAbis.joinToString(", ")}",
            )
        } else {
            DiagnosticCheck(
                id = "abi",
                title = "Архитектура процессора",
                status = CheckStatus.ERROR,
                detail = "Устройство: $abi — сборка для этой архитектуры отсутствует",
                recommendation = "Установите сборку для своей архитектуры",
                technical = "Поддерживаемые: ${SUPPORTED_ABIS.joinToString(", ")}",
            )
        }
    }

    private fun nativeLibs(s: DeviceSnapshot): DiagnosticCheck {
        val present = s.wgLibPresent
        if (present == null) {
            return DiagnosticCheck(
                id = "native_libs",
                title = "Нативные библиотеки",
                status = CheckStatus.SKIPPED,
                detail = "Не удалось проверить каталог библиотек",
                technical = s.bundledNativeLibs.joinToString(", ").ifEmpty { null },
            )
        }
        val bundled = s.bundledNativeLibs
        return if (present) {
            DiagnosticCheck(
                id = "native_libs",
                title = "Нативные библиотеки",
                status = CheckStatus.OK,
                detail = "Движок туннеля на месте",
                technical = "В APK: ${bundled.size} библиотек" +
                    if (bundled.isEmpty()) "" else " (${bundled.joinToString(", ")})",
            )
        } else {
            DiagnosticCheck(
                id = "native_libs",
                title = "Нативные библиотеки",
                status = CheckStatus.ERROR,
                detail = "Не найден libwg-go.so — движок не сможет поднять туннель",
                recommendation = "Переустановите приложение: сборка повреждена или не подходит по ABI",
            )
        }
    }

    private fun pageSize(s: DeviceSnapshot): DiagnosticCheck {
        val ps = s.pageSizeBytes
        if (ps == null) {
            return DiagnosticCheck(
                id = "page_size",
                title = "Размер страницы памяти",
                status = CheckStatus.SKIPPED,
                detail = "Не удалось определить",
            )
        }
        return if (ps >= SIXTEEN_KB) {
            DiagnosticCheck(
                id = "page_size",
                title = "Размер страницы памяти",
                status = CheckStatus.OK,
                detail = "${ps / 1024} КБ — совместимо с требованиями Android 15+",
                technical = "Страницы: ${ps / 1024} КБ",
            )
        } else {
            // 4 KB pages are still the norm on older devices; a native library aligned
            // to 16 KB *also* runs on 4 KB, so this is informational, not a failure.
            DiagnosticCheck(
                id = "page_size",
                title = "Размер страницы памяти",
                status = CheckStatus.OK,
                detail = "${ps / 1024} КБ — стандартный режим",
            )
        }
    }

    private fun vpnApi(s: DeviceSnapshot): DiagnosticCheck {
        val noVpn = s.vpnPermissionGranted
        return DiagnosticCheck(
            id = "vpn_api",
            title = "Системный VPN API",
            status = if (s.sdkInt == null) CheckStatus.SKIPPED else CheckStatus.OK,
            detail = if (noVpn == null) {
                "Доступен, состояние разрешения неизвестно"
            } else if (noVpn) {
                "Доступен, разрешение выдано"
            } else {
                "Доступен, разрешение ещё не выдано"
            },
        )
    }

    private fun vpnPermission(s: DeviceSnapshot): DiagnosticCheck {
        val granted = s.vpnPermissionGranted
        if (granted == null) {
            return DiagnosticCheck(
                id = "vpn_permission",
                title = "Разрешение на VPN",
                status = CheckStatus.SKIPPED,
                detail = "Система не сообщает состояние до явной попытки подключения",
                recommendation = "Подключитесь один раз — Android покажет запрос",
            )
        }
        return if (granted) {
            DiagnosticCheck(
                id = "vpn_permission",
                title = "Разрешение на VPN",
                status = CheckStatus.OK,
                detail = "Выдано",
            )
        } else {
            DiagnosticCheck(
                id = "vpn_permission",
                title = "Разрешение на VPN",
                status = CheckStatus.WARNING,
                detail = "Ещё не выдано",
                recommendation = "Нажмите «Подключить» и разрешите VPN в системном диалоге",
            )
        }
    }

    private fun conflictingVpn(s: DeviceSnapshot): DiagnosticCheck {
        val active = s.vpnTransportActive
        if (active == null) {
            return DiagnosticCheck(
                id = "conflicting_vpn",
                title = "Другие VPN",
                status = CheckStatus.SKIPPED,
                detail = "Определить активный VPN нельзя без системного запроса",
            )
        }
        return if (active) {
            DiagnosticCheck(
                id = "conflicting_vpn",
                title = "Другие VPN",
                status = CheckStatus.WARNING,
                detail = "Обнаружен активный VPN-транспорт",
                recommendation = "Android разрешает только один VPN. Отключите другие VPN-приложения, если подключение не устанавливается",
                technical = "NetworkCapabilities.TRANSPORT_VPN",
            )
        } else {
            DiagnosticCheck(
                id = "conflicting_vpn",
                title = "Другие VPN",
                status = CheckStatus.OK,
                detail = "Конфликтующих VPN не обнаружено",
            )
        }
    }

    private fun network(s: DeviceSnapshot): DiagnosticCheck {
        val available = s.networkAvailable
        val transports = s.networkTransports
        val transportText = when {
            transports.isEmpty() -> "тип не определён"
            else -> transports.joinToString(", ")
        }
        if (available == null) {
            return DiagnosticCheck(
                id = "network",
                title = "Сеть",
                status = CheckStatus.SKIPPED,
                detail = "Состояние сети недоступно",
            )
        }
        return if (available) {
            DiagnosticCheck(
                id = "network",
                title = "Сеть",
                status = CheckStatus.OK,
                detail = "Есть подключение ($transportText)",
            )
        } else {
            DiagnosticCheck(
                id = "network",
                title = "Сеть",
                status = CheckStatus.ERROR,
                detail = "Нет подключения к интернету",
                recommendation = "Включите Wi-Fi или мобильные данные",
                technical = "Транспорты: $transportText",
            )
        }
    }

    private fun dns(s: DeviceSnapshot): DiagnosticCheck {
        val resolved = s.dnsResolved
        val host = s.dnsResolvedHost
        if (resolved == null) {
            return DiagnosticCheck(
                id = "dns",
                title = "DNS",
                status = CheckStatus.SKIPPED,
                detail = "Проверка DNS не выполнялась",
            )
        }
        return if (resolved) {
            DiagnosticCheck(
                id = "dns",
                title = "DNS",
                status = CheckStatus.OK,
                detail = "Имя ${host ?: ""} разрешается".trim(),
                technical = host,
            )
        } else {
            DiagnosticCheck(
                id = "dns",
                title = "DNS",
                status = CheckStatus.WARNING,
                detail = "Не удалось разрешить ${host ?: "тестовое имя"}",
                recommendation = "Проверьте DNS в настройках или в конфигурации профиля",
                technical = "Ошибка разрешения имени",
            )
        }
    }

    private fun notifications(s: DeviceSnapshot): DiagnosticCheck {
        val enabled = s.notificationsEnabled
        if (enabled == null) {
            return DiagnosticCheck(
                id = "notifications",
                title = "Уведомления",
                status = CheckStatus.SKIPPED,
                detail = "Состояние разрешения недоступно",
            )
        }
        return if (enabled) {
            DiagnosticCheck(
                id = "notifications",
                title = "Уведомления",
                status = CheckStatus.OK,
                detail = "Разрешены — статус туннеля виден в шторке",
            )
        } else {
            DiagnosticCheck(
                id = "notifications",
                title = "Уведомления",
                status = CheckStatus.WARNING,
                detail = "Запрещены — статус туннеля не будет виден",
                recommendation = "Разрешите уведомления в настройках приложения",
                technical = "POST_NOTIFICATIONS",
            )
        }
    }

    private fun backgroundRestrictions(s: DeviceSnapshot): DiagnosticCheck {
        val restricted = s.backgroundRestricted ?: return DiagnosticCheck(
            id = "background",
            title = "Фоновая работа",
            status = CheckStatus.SKIPPED,
            detail = "Ограничения фона не удалось определить",
        )
        val batteryOpt = s.ignoringBatteryOptimizations
        return when {
            restricted -> DiagnosticCheck(
                id = "background",
                title = "Фоновая работа",
                status = CheckStatus.ERROR,
                detail = "Фоновая работа ограничена системой",
                recommendation = "Снимите ограничение батареи для приложения — иначе туннель будет обрываться",
                technical = "ActivityManager.isBackgroundRestricted=true",
            )
            batteryOpt == false -> DiagnosticCheck(
                id = "background",
                title = "Фоновая работа",
                status = CheckStatus.WARNING,
                detail = "Оптимизация батареи может приостанавливать туннель",
                recommendation = "Разрешите работу без ограничений батареи для стабильного соединения",
                technical = "Игнорирование оптимизации батареи выключено",
            )
            else -> DiagnosticCheck(
                id = "background",
                title = "Фоновая работа",
                status = CheckStatus.OK,
                detail = "Ограничений не обнаружено",
            )
        }
    }

    private fun storage(s: DeviceSnapshot): DiagnosticCheck {
        val free = s.freeStorageBytes ?: return DiagnosticCheck(
            id = "storage",
            title = "Хранилище",
            status = CheckStatus.SKIPPED,
            detail = "Свободное место не определено",
        )
        val writable = s.filesDirWritable
        if (writable == false) {
            return DiagnosticCheck(
                id = "storage",
                title = "Хранилище",
                status = CheckStatus.ERROR,
                detail = "Каталог приложения недоступен для записи",
                recommendation = "Освободите место или переустановите приложение",
            )
        }
        val human = formatBytes(free)
        return if (free < LOW_STORAGE_BYTES) {
            DiagnosticCheck(
                id = "storage",
                title = "Хранилище",
                status = CheckStatus.WARNING,
                detail = "Мало свободного места: $human",
                recommendation = "Освободите место — журналы и обновления могут не сохраниться",
            )
        } else {
            DiagnosticCheck(
                id = "storage",
                title = "Хранилище",
                status = CheckStatus.OK,
                detail = "Свободно $human",
            )
        }
    }

    private fun memory(s: DeviceSnapshot): DiagnosticCheck {
        val free = s.freeMemoryBytes ?: return DiagnosticCheck(
            id = "memory",
            title = "Память",
            status = CheckStatus.SKIPPED,
            detail = "Свободная память не определена",
        )
        val total = s.totalMemoryBytes
        val detail = "Свободно ${formatBytes(free)}" + if (total != null) " из ${formatBytes(total)}" else ""
        return if (free < LOW_MEMORY_BYTES) {
            DiagnosticCheck(
                id = "memory",
                title = "Память",
                status = CheckStatus.WARNING,
                detail = detail,
                recommendation = "Закройте лишние приложения — движку нужно место для запуска",
                technical = "Порог предупреждения: ${formatBytes(LOW_MEMORY_BYTES)}",
            )
        } else {
            DiagnosticCheck(
                id = "memory",
                title = "Память",
                status = CheckStatus.OK,
                detail = detail,
            )
        }
    }

    private fun wdttEngine(s: DeviceSnapshot): DiagnosticCheck {
        val present = s.wdttClientPresent
        if (present == null) {
            return DiagnosticCheck(
                id = "wdtt_engine",
                title = "Движок WDTT",
                status = CheckStatus.SKIPPED,
                detail = "Наличие не проверялось",
            )
        }
        // A missing WDTT client is NOT an error: WG/AWG work without it, and this build
        // may legitimately ship without that engine. Only mention it as context.
        return if (present) {
            DiagnosticCheck(
                id = "wdtt_engine",
                title = "Движок WDTT",
                status = CheckStatus.OK,
                detail = "Встроенный клиент на месте",
                technical = "libclient.so",
            )
        } else {
            DiagnosticCheck(
                id = "wdtt_engine",
                title = "Движок WDTT",
                status = CheckStatus.SKIPPED,
                detail = "В этой сборке WDTT не bundled — профили WDTT работать не будут",
                recommendation = "Используйте WireGuard или AmneziaWG",
                technical = "libclient.so отсутствует",
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Binary units, consistent with the traffic screen. */
    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1024 -> "$b Б"
            b < 1024L * 1024 -> "${b / 1024} КБ"
            b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f МБ", b / (1024.0 * 1024))
            else -> String.format(java.util.Locale.US, "%.2f ГБ", b / (1024.0 * 1024 * 1024))
        }
    }
}
