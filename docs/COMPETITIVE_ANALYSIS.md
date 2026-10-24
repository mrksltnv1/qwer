# Конкурентный аудит: MCRF, Happ Android, WDTT Plus → qwer

Дата: 2026-10-24 · Наш проект: qwer 0.8.9 · Аудит без изменений кода приложения

## 1. Краткое резюме

**Проверено 3/3 репозитория, но один — без исходников.**

- **MCRF** — 2127 файлов, 448 Kotlin-файлов Android-части. Модульная архитектура движков через SPI, подписки, маршрутизация, QR, шифрованные бэкапы. Ближайший ориентир.
- **Happ Android** — **4 файла, исходного кода нет.** Только README, JSON-манифест релиза и CI-валидатор. Анализ реализации невозможен; доступны лишь заявленные в README функции.
- **WDTT Plus** — 355 файлов, GPLv3. Сильнейшая в тройке диагностика устройства и понятные тексты ошибок.

**У нас (qwer 0.8.9):** 5681 строк кода, 33 Kotlin-файла, 9 тест-файлов (1010 строк, 77 тестов).
Работает: WG/AWG/WDTT, failover, health-probe, foreground-сервис, уведомление, QRS-плитка, логи, статистика трафика с таймером, импорт из файла/буфера, автообновление, Keystore.

**Чего нет (проверено грепом по коду, совпадения оказались комментариями):** маршрутизация, бэкап/восстановление, QR, подписки, локальный прокси, GeoIP/GeoSite, выбор движка.

**Главный вывод:** нам не нужны новые протоколы. Нужны три вещи — **диагностика устройства**, **понятные ошибки**, **резервные копии**. Это дёшево, не трогает архитектуру и закрывает большинство жалоб.

---

## 2. Архитектура нашего приложения

| Параметр | Значение |
|---|---|
| Язык / UI | Kotlin 2.2.20 / Jetpack Compose (BOM 2024.12.01) |
| SDK | compileSdk 36, minSdk 24, targetSdk 34 |
| Сборка | AGP 8.13.0, Gradle 8.13, JDK 21 |
| Движок | `org.amnezia.awg` AAR 3.1.4 — один на WG и AWG |
| VDTT | встроен: `libclient.so` как процесс, конфиг из stdout |

Пакеты (`app/src/main/java/com/qwer/app/`):

- `core/` — ConnectionManager, ConnectionState, NetworkLifecycleMonitor, NetworkChangeGate, TrafficStats, ProfileRepository
- `engine/` — TunnelEngine (обёртка AWG), WdttPlusAdapter
- `failover/` — FailoverController (чистый автомат)
- `health/` — TcpProbe с общим дедлайном
- `config/` — ConfigImporter, ConfigNormalizer, FileConfigReader
- `secure/` — KeystoreConfigStore (AES-GCM)
- `service/` — ConnectionForegroundService, VpnNotification, VpnControlReceiver, VpnTileService
- `ui/` — MainActivity, MainScreen, LogsScreen, AppBottomBar, Theme
- `update/` — UpdateChecker, UpdateInstaller
- `diagnostics/` — Diagnostics (ring buffer 500 строк, scrubbing)

Сильные стороны, которые уже есть и не требуют правок:
- единый `ConnectionState` — единственный источник правды для UI и уведомления;
- `Connected` только после успешной пробы через туннель;
- сериализация восстановления через `recoveryMutex` + `NetworkChangeGate`;
- Keystore, scrubbing секретов, отсутствие ключей в логах.

---

## 3. Анализ MCRF

Репозиторий: 200 МБ, ветка `3e77d44`. Android: `ports/android/app/`.

### Реализации, заслуживающие внимания

**SPI движков** — `work/steer-android-port/android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1/Engine.java`:

```java
interface Engine {
    int SPI_VERSION = 1;
    Validation validate(String profile, String settings, Mode mode);   // VPN | PROXY
    String tunPlanJson(...);        // хост применяет план, модуль НЕ создаёт VpnService
    void run(Request request, Host host) throws Exception;
    void requestStop();
    Counters counters();
    Stats stats();                  // Metric: null = недоступно, 0 = наблюдаемый ноль
}
```

Ключевая идея — **`Metric` с `null` = «нет данных»**, а не 0. У нас то же решение в `TrafficSnapshot.Unavailable`, принято независимо.

**Маршрутизация** — `PacketIpRoutes.kt`:
```kotlin
enum class Target { VPN, DIRECT, BLOCK }
data class Rule(val cidrs: List<String>, val target: Target)
```
Плюс `RoutingPage.kt`, `RoutingDefaultsRussianApps.kt`, `GeoDataConfig.kt`, `AdvancedDnsConfig.kt`, `AppSelectionList.kt` (per-app маршруты, иконки в `LruCache`, загрузка иконок в отдельном пуле — I/O никогда в `getView`).

**QR** — `QrCodec.kt`: zxing, лимит 2953 байта UTF-8, размер 128–2048, `ErrorCorrectionLevel.M`. Декод с жёсткими лимитами (`width*height <= 8_388_608`) — защита от OOM.

**Бэкап** — `BackupCodec.kt`: `AES/GCM/NoPadding`, 16-байтный тег, `PBKDF2WithHmacSHA256`, аутентифицированный заголовок, ограниченная декомпрессия. Комментарий в коде прямо говорит «no Keystore export» — то есть переносимый шифртекст, но не экспорт ключа.

**Обновление движков** — `EnginePackage.kt`, `EngineUpdates.kt`, `EngineUpdateGate.kt`, `EngineUpdateSchedule.kt`: движки как отдельные APK-пакеты, скачиваются из каталога, проверка контрольных сумм.

### Ограничения

- **Лицензия в репозитории отсутствует.** README: «Исходники, лицензии и контрольные суммы приложены к релизам». Брать код нельзя без проверки лицензии конкретного релиза.
- Размер: 200 МБ репозитория, 448 Kotlin-файлов — это продукт другого масштаба; целиком переносить нечего.
- Android 10+ (у нас minSdk 24 — часть API недоступна).

---

## 4. Анализ Happ Android

**Исходного кода нет.** Всё содержимое репозитория:

```
.github/workflows/validate-release.yml   (валидирует JSON через jq)
.idea/.gitignore
README.md
release                                   (JSON, 2.2 КБ)
```

По README (заявлено, **не подтверждено кодом**): Xray core, VLESS (Reality), VMess, Trojan, Shadowsocks, Socks, гибкие правила маршрутизации, отсутствие сбора данных.

**Вывод:** анализировать реализацию невозможно. Ни одна функция Happ не может быть обоснована ссылкой на код, поэтому в сравнительную таблицу они не включены. Для UX-идей можно ориентироваться только на описание и скриншоты из README, что доказательством не является.

---

## 5. Анализ WDTT Plus

355 файлов, **GPLv3** (`LICENSE`).

### Что действительно хорошо

**Диагностика устройства** — `DeviceCompatibility.kt` (1449 строк), ~20 независимых проверок:

```
abiItem, androidVersionItem, memoryClassItem, storageItem, batteryItem,
pageSizeItem, pageSizeCompatibilityItem, nativeComponentsItem,
nativeRuntimeSafetyItem, notificationPermissionItem, processExitItem,
processExitHistoryItem, lastTunnelStopItem, connectionHoldRuntimeItem,
sleepBatteryModeItem, trustedWifiModeItem, vpnRoutingModeItem, vpnDnsModeItem
```

Есть проверка **16-КиБ страниц памяти** (`pageSizeCompatibilityItem`) — это ровно та проблема, из-за которой у нас падала установка на Android 16. И `processExitHistoryItem` — история падений процесса с классификацией причин.

**Понятные ошибки** — `ConnectionDiagnostics.kt`: событие `FAILED <epoch> <path> <stage> <reason>` разбирается в человеческий текст:

```
stage:  turn → «подключение к ретранслятору», dtls → «защищённое соединение с сервером»
reason: timeout → «истекло время ожидания», auth → «доступ не подтверждён»
```

И критично: событие принимается только если `networkEpoch` совпадает с текущим — защита от устаревших ошибок после смены сети.

**`TunnelStartRequestGate.kt`** — generation-счётчик:
```kotlin
internal class TunnelStartRequestGate {
    private var generation: Long = 0
    fun next(): Long; fun invalidate(); fun isCurrent(value: Long): Boolean
}
```
Не даёт отложенному старту сервиса оживить устаревший запрос. У нас роль играет `recoveryMutex` + отмена Job, но generation-подход чище для `onStartCommand`.

**Статистика с восстановлением** — `TunnelSessionTraffic.kt`: аккумулятор с `downloadOffsetMb`/`lastRawDownloadMb` и `restore(snapshot)`. Позволяет сохранить счётчики при пересоздании процесса — у нас сессия начинается с нуля.

**Логи** — `ui/LogsTab.kt`, `ClientNetworkDiagnostics.kt` с группировкой повторяющихся событий.

**Прочее:** `QrCaptureActivity.kt`, `TransferCodec.kt`, `ProfileTransfer.kt` (передача профилей), `QuickToggleTileService.kt` (плитка — у нас есть), `TunnelStopCoordinator.kt`, `AccessLifecycle*.kt`.

### Ограничения

- **GPLv3** — любое копирование кода обязывает лицензировать qwer под GPLv3 (у нас уже так, но это надо подтвердить осознанно).
- Транспорт (TURN/VK/DTLS) нерелевантен нашей архитектуре.
- Основной объём — `DeployTab.kt` (17 639 строк) и `SettingsTab.kt` (7879) — это UI-монолиты, не образец для подражания.

---

## 6. Сравнительная таблица функций

| Функция | Проект | Реализация в источнике | Есть у нас | Проблема, которую решает | Польза | Сложность | Риски | Изменения в архитектуре | Приоритет |
|---|---|---|---|---|---|---|---|---|---|
| Диагностика устройства | WDTT | `DeviceCompatibility.kt`, ~20 проверок | ❌ | Непонятно, почему не работает | Высокая | Низкая | Нет | Новый `device/DeviceReport.kt` + экран | **P0** |
| Понятные ошибки по стадиям | WDTT | `ConnectionDiagnostics.kt`, epoch-guard | ⚠️ частично | «Не удалось подключиться» без причины | Высокая | Низкая | Нет | `humanEngineError` + `Diagnostics` | **P0** |
| Шифрованный бэкап | MCRF | `BackupCodec.kt` AES-GCM+PBKDF2 | ❌ | Потеря профилей при смене телефона | Высокая | Средняя | Слабый пароль | Новый `backup/` | **P0** |
| Generation-защита старта | WDTT | `TunnelStartRequestGate.kt` | ⚠️ иначе | Устаревший старт после stop | Средняя | Низкая | Нет | `ConnectionForegroundService` | **P1** |
| QR-импорт/экспорт | MCRF | `QrCodec.kt` zxing, лимит 2953 Б | ❌ | Долгий ручной ввод | Средняя | Средняя | +зависимость zxing | `deeplink/` + новый экран | P2 |
| Маршрутизация per-app | MCRF | `AppSelectionList.kt`, `PacketIpRoutes.kt` | ❌ | Весь трафик в VPN | Высокая | **Высокая** | Ломает туннель при ошибке | Движок + UI + модель правил | P2 |
| Подписки + автопродление | MCRF | `EngineUpdateSchedule`, каталог | ❌ | Ручное обновление серверов | Средняя | Высокая | Нужен сервер | Новый слой | P3 |
| Модульные движки (SPI) | MCRF | `Engine.java` интерфейс | ❌ | Один движок на всё | Средняя | **Высокая** | native-конфликты | Перестройка engine/ | P3 |
| Восстановление статистики | WDTT | `TunnelSessionTraffic.kt` | ❌ | Счётчики с нуля после рестарта | Низкая | Средняя | Рассинхрон | `TrafficStats` | P2 |
| Группировка логов | WDTT | `LogsTab.kt` | ⚠️ фильтр есть | Одинаковые строки забивают буфер | Средняя | Низкая | Нет | `LogsScreen` | P1 |

⚠️ = частично. Все «есть у нас» проверены чтением кода, не README.

---

## 7. Функции, которые у нас уже реализованы

Не предлагать повторно: WG/AWG/WDTT-подключение, failover с hysteresis (12 с окно, 60 с cooldown, бюджет 3), health-probe с общим дедлайном, foreground-сервис + уведомление с протоколом, QS-плитка, экран логов с копированием, статистика RX/TX + таймер сессии, импорт из файла/буфера/шаринга, deep link `qwer://import`, автообновление приложения, Keystore-хранение, scrubbing секретов, NetworkChangeGate (5 с окно), сериализация восстановления.

---

## 8. Функции, которые стоит улучшить

1. **`humanEngineError`** (`core/ConnectionManager.kt`) — сейчас 8 веток по строковому совпадению. Заменить на разбор `BackendException.Reason` по стадиям, как в `ConnectionDiagnostics.kt`.
2. **Логи** (`ui/LogsScreen.kt`) — нет группировки. `NETWORK_EVENT`/`PROBE_RESULT` повторяются сотнями. Схлопывать одинаковые подряд с счётчиком `×N`.
3. **`TunnelEngine.counters()`** — при ошибке возвращает `Counters(0,0)`, то есть «ноль», а не «неизвестно». Противоречит нашей же модели `TrafficSnapshot.Unavailable`.
4. **`Diagностика` при отказе** — нет снимка окружения (ABI, ABI-list, SDK, свободная память, размер страницы). WDTT это делает, мы нет.

---

## 9. Функции, которые стоит добавить

**P0 (немедленно):**
- Диагностика устройства (`DeviceReport`)
- Структурированные ошибки по стадиям
- Шифрованный экспорт/импорт профилей

**P1:**
- Группировка логов
- Generation-защита старта сервиса
- Кнопка «Скопировать отчёт» с окружением устройства

**P2:** QR, восстановление статистики, маршрутизация per-app
**P3:** подписки, SPI движков

---

## 10. Roadmap P0–P3

| Приоритет | Задача | Сложность | Модули |
|---|---|---|---|
| P0 | Диагностика устройства | низкая | `device/` (новый), `ui/` |
| P0 | Ошибки по стадиям | низкая | `core/ConnectionManager`, `engine/` |
| P0 | Бэкап профилей | средняя | `backup/` (новый), `secure/` |
| P1 | Группировка логов | низкая | `ui/LogsScreen` |
| P1 | Generation-защита | низкая | `service/ConnectionForegroundService` |
| P2 | QR-импорт | средняя | `deeplink/`, `ui/` |
| P2 | Восстановление статистики | средняя | `core/TrafficStats` |
| P2 | Per-app маршрутизация | высокая | `engine/`, `core/`, `ui/` |
| P3 | Подписки | высокая | новый слой |
| P3 | SPI движков | высокая | перестройка `engine/` |

---

## 11. Пять планов внедрения

### План 1. Диагностика устройства (P0)

**Сейчас:** при проблемах пользователь видит только коды ошибок движка.
**Цель:** экран «Диагностика» со снимком окружения.

Новые файлы:
- `device/DeviceReport.kt` — data class + сбор
- `device/DeviceChecks.kt` — чистые проверки (тестируемые)
- `ui/DiagnosticsScreen.kt`

Проверки (по образцу `DeviceCompatibility.kt`):
ABI и abilist, SDK, manufacturer/model, размер страницы памяти (16 КБ), наличие/размер `libwg-go.so`/`libclient.so`, свободная память, разрешение уведомлений, статус FGS, последняя ошибка движка, версия приложения.

Контракт:
```kotlin
data class CheckResult(val id: String, val ok: Boolean?, val detail: String)
fun collect(context: Context): List<CheckResult>   // ok=null → «не удалось определить»
```
`null` вместо `false` — не путать «не проверили» с «плохо».

Тесты: 6+ на чистую логику (без Android), 1 ручной — открыть экран и скопировать отчёт.
Приёмка: отчёт копируется, содержит ABI/SDK/16 КБ/память, на устройстве без WDTT пишет «не установлен», а не «ошибка».
Откат: экран отдельный, убрать точку входа — остальное не затронуто.

### План 2. Ошибки по стадиям (P0)

**Сейчас:** `humanEngineError` — 8 веток через `msg.contains(...)`.
**Цель:** разбор `BackendException.Reason` + та же модель для WDTT.

Изменения:
- `core/ConnectionManager.kt` — заменить `humanEngineError`
- `engine/TunnelEngine.kt` — пробросить `Reason` вместо строки
- `core/ConnectionState.kt` — `FailureReason` расширить стадиями

Новый тип:
```kotlin
sealed interface EngineFailure {
    data class Reason(val code: String, val stage: Stage, val detail: String?) : EngineFailure
    data class Unknown(val raw: String) : EngineFailure
}
```
Тексты — по образцу `ConnectionDiagnostics.kt` (стадия → «подключение к ретранслятору», причина → «истекло время ожидания»).

Тесты: по одному на каждое `BackendException.Reason` + на неизвестную причину (должна показывать сырой код, не «неизвестная ошибка»).
Приёмка: для каждой причины из AAR есть человеческий текст; неизвестная причина не теряется.
Откат: старый метод оставить как `@Deprecated` на один релиз.

### План 3. Шифрованный бэкап (P0)

**Сейчас:** бэкапа нет; потеря телефона = потеря всех профилей.
**Цель:** экспорт/импорт файла, совместимого только с qwer.

Новые файлы:
- `backup/BackupCodec.kt` — AES-GCM + PBKDF2WithHmacSHA256 (по образцу MCRF)
- `backup/BackupModel.kt` — версия формата + профили
- `ui/BackupScreen.kt`

Формат: `magic(4) + version(2) + salt(16) + iv(12) + ciphertext + tag(16)`, AAD = заголовок.
Пароль → PBKDF2 (>= 210_000 итераций), ключ не экспортируется из Keystore.
Внутри — **профили без конфигов либо конфиги в шифртексте**; решение зафиксировать явно.

Тесты: round-trip, неверный пароль → отказ без падения, обрезанный файл → отказ, подмена AAD → отказ, лимит размера.
Приёмка: экспорт→импорт на чистой установке восстанавливает все профили и активный.
Откат: формат версионирован, старые версии читаются; при регрессе — отключить пункт меню.

### План 4. Группировка логов (P1)

**Сейчас:** 500 строк, одинаковые события подряд.
**Цель:** `PROBE_RESULT ok=true` ×40 вместо 40 строк.

Изменения: `ui/LogsScreen.kt` + `diagnostics/Diagnostics.kt` (хранить `count` у записи).
Правило: схлопывать только **соседние** записи с одинаковым `event + ключевые поля`; разные `fails` не схлопывать.
Тесты: 4 на редьюсер (чистая функция).
Приёмка: в логе на 500 записей видно не более ~50 визуальных строк при стабильной сети.
Откат: флаг «не группировать» в том же экране.

### План 5. Generation-защита старта (P1)

**Сейчас:** `START_STICKY` + отмена Job; при stop во время старта возможен «оживший» туннель.
**Цель:** как `TunnelStartRequestGate.kt`.

Изменения: `service/ConnectionForegroundService.kt`, `core/ConnectionManager.kt`.
```kotlin
class StartRequestGate {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun isCurrent(v: Long) = v == generation
}
```
Тесты: 5 (next/invalidate/isCurrent, гонка двух стартов).
Приёмка: stop во время старта не оставляет активный туннель; повторный start работает.
Откат: класс изолирован, убрать вызовы.

---

## 12. Улучшения UX

Обосновано кодом источников, не вкусовщиной.

1. **Экран «Диагностика»** (WDTT `DeviceCompatibility`): одна кнопка «Проверить устройство» + «Скопировать отчёт». У нас сейчас нет способа понять, почему не работает.
2. **Ошибка = стадия + причина + действие.** Вместо «Не удалось подключиться» → «Не удалось разрешить адрес сервера. Проверьте DNS или подключение» (модель `ConnectionDiagnostics.kt`).
3. **Группировка логов** с счётчиком `×N` — прямое следствие твоего лога с 14 одинаковыми `NETWORK_CHANGED`.
4. **Блок статистики** уже хорош; добавить `Unavailable` вместо `0 B` (наш `TunnelEngine.counters()` это нарушает).
5. **Не менять** существующий визуальный стиль: он рабочий, а перенос UI-монолитов WDTT (`DeployTab.kt`, 17 639 строк) — антипаттерн.

---

## 13. Проблемы безопасности и технические риски

**Подтверждённые дефекты нашего кода:**

| Дефект | Файл | Риск |
|---|---|---|
| `counters()` возвращает `Counters(0,0)` при ошибке | `engine/TunnelEngine.kt:78-82` | Ложные нули; UI не отличает «нет трафика» от «нет данных» |
| Строковое сопоставление ошибок | `core/ConnectionManager.kt` `humanEngineError` | При смене текста в AAR ошибки деградируют в «не удалось» |
| Логи без группировки | `ui/LogsScreen.kt` | Буфер 500 строк вытесняется шумом |

**Гипотезы (требуют воспроизведения, не подтверждены):**
- Утечка `WdttClient` при отмене во время `awaitConfig`.
- Возможен «оживший» туннель при `stop` во время `Connecting` (покрывается планом 5).

**Риски источников:**
- MCRF: лицензия отсутствует в репозитории → **код брать нельзя**.
- Happ: кода нет; доверять README как доказательству нельзя.
- WDTT: GPLv3 → копирование обязывает GPLv3 для qwer.

---

## 14. Лицензионные ограничения

| Проект | Лицензия | Что можно |
|---|---|---|
| **MCRF** | **Не указана в репозитории** (README: «лицензии приложены к релизам») | Только идеи и архитектурные решения. Код не копировать до проверки лицензии конкретного релиза |
| **Happ Android** | Не указана | Кода нет — вопрос неактуален |
| **WDTT Plus** | **GPLv3** | Копирование → qwer обязан быть GPLv3 (сейчас так и есть), но это осознанное решение |

**Рекомендация:** все три улучшения P0 реализовать **самостоятельно** по описанию алгоритма, без копирования кода. AES-GCM+PBKDF2 и список проверок устройства — стандартные техники, не требующие заимствования.

---

## 15. План тестирования

**Юнит (расширение с 77):**
- `DeviceChecksTest` — 6: ABI, 16 КБ, память, отсутствие библиотеки
- `EngineFailureTest` — 9: все `Reason` + неизвестная
- `BackupCodecTest` — 8: round-trip, неверный пароль, обрезка, подмена AAD, лимит
- `LogGroupingTest` — 4: соседние/разные ключи
- `StartRequestGateTest` — 5

**Инструментальные (ручные, на устройстве):**
- экспорт→импорт на чистой установке
- диагностика при выключенном VPN и без WDTT
- stop во время Connecting
- логи при 10 минутах стабильной сети

**Регрессия:** существующие 77 тестов должны остаться зелёными; прогон на arm64 и x86_64.

---

## 16. Итоговая рекомендация

**Первым — диагностика устройства.** Она даёт максимум пользы за минимум изменений: не трогает туннель, не требует зависимостей, отвечает на вопрос «почему не работает» и уже несколько раз пригодилась бы в этой сессии (ABI, 16 КБ страницы, разрешение уведомлений — каждая из этих проблем стоила отдельного круга отладки).

**Вторым — ошибки по стадиям.** Дёшево, убирает главную причину беспомощных жалоб.

**Третьим — бэкап.** Единственная функция, потеря которой необратима для пользователя.

Маршрутизацию, подписки и SPI движков — **не сейчас**: высокая сложность, ломают рабочую архитектуру, а текущая стабильность ещё не подтверждена полем.
