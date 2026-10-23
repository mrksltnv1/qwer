# MyVPN — минималистичный клиент WireGuard / AmneziaWG / WDTT Plus

Android-приложение (Kotlin + Jetpack Compose + Material 3) для подключения к VPN
по трём протоколам с автоматическим переключением на резервный профиль.

## Что реально работает (проверено на устройстве)

| Функция | Статус | Доказательство |
| --- | --- | --- |
| Сборка debug APK | ✅ | `app-debug.apk`, 30 МБ, BUILD SUCCESSFUL |
| Юнит-тесты | ✅ 39/39 | 4 набора, 0 падений |
| Установка и запуск | ✅ | BlissOS Android 13 (SDK 33), без крашей |
| WireGuard туннель | ✅ | Сервер: `latest handshake: 34s ago`, `transfer: 21.27 KiB received` |
| AmneziaWG | ⚠️ движок тот же, обфускация не проверена на сервере | см. ниже |
| Failover WG → WDTT | ✅ сработал за 13 с на реальном отказе | лог `FAILOVER_TRIGGERED` |
| Восстановление после отказа | ✅ | повторное подключение, handshake подтверждён |
| WDTT Plus как движок | ❌ только handover | GPLv3 + `exported=false` |
| Deep links `myvpn://import` | ✅ парсинг + валидация | 8 юнит-тестов |
| Импорт с сервера по токену | ❌ сервер не реализован | нужен backend |

## Ключевое архитектурное решение

**Один движок вместо двух.** AmneziaWG-парсер принимает обычный WireGuard-конфиг и
оставляет поля обфускации пустыми — проверено на собранном AAR:

```
PLAIN_WG_PARSE: OK peers=1  jc=Optional.empty s1=Optional.empty h1=Optional.empty
AWG_PARSE:      OK jc=Optional[4] h4=Optional[4]
```

Это снимает конфликт упаковки: WireGuard AAR и AmneziaWG AAR оба кладут в APK
`libwg-go.so`, `libwg.so`, `libwg-quick.so`. Два AAR рядом = битые native-библиотеки.
Одно семейство имён = один движок.

## Быстрая сборка

```bash
# На сборочном хосте (Debian 13 + JDK 21 + Android SDK 36 + NDK 26.1)
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd vpn-mvp
./gradlew :app:testDebugUnitTest :app:assembleDebug

# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Сборка AAR движка AmneziaWG (нужна один раз):

```bash
git clone --recurse-submodules https://github.com/amnezia-vpn/amneziawg-android
cd amneziawg-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :tunnel:assembleRelease
cp tunnel/build/outputs/aar/tunnel-release.aar ../vpn-mvp/app/libs/awg-tunnel.aar
```

## Структура

```
app/src/main/java/com/myvpn/app/
├── core/          ConnectionManager, ConnectionState, Protocol, ProfileRepository
├── engine/        TunnelEngine (AmneziaWG), WdttPlusAdapter (handover)
├── failover/      FailoverController — чистый автомат, без Android
├── health/        ConnectivityProbe, TcpProbe (общий дедлайн)
├── config/        ConfigImporter — валидация WG/AWG без секретов в модели
├── deeplink/      DeepLinkHandler — myvpn://import
├── secure/        KeystoreConfigStore — AES-GCM через Android Keystore
├── diagnostics/   Diagnostics — структурные события со scrubbing
└── ui/            MainActivity, MainScreen, Theme
```

## Лицензии

| Компонент | Лицензия | Влияние |
| --- | --- | --- |
| AmneziaWG `:tunnel` (AAR) | Apache-2.0 | совместимо с любым продуктом |
| WireGuard `tunnel` (Maven) | Apache-2.0 | не используется (см. выше) |
| WDTT Plus | **GPLv3** | **не встроен**; только handover |

Если потребуется настоящий встроенный WDTT-движок (бесшовный failover), продукт
обязан стать GPLv3. Это решение владельца продукта, не техническое.

## Ограничения

1. **WDTT Plus не встраивается**: `TunnelService` объявлен `exported="false"`, лицензия GPLv3.
2. **Android разрешает один VpnService** — два VPN одновременно невозможны, поэтому
   «переключение» = остановка WG и передача управления WDTT Plus.
3. **Тестовое окружение — виртуалка** (BlissOS на QEMU/KVM). Смена Wi-Fi ↔ LTE,
   Doze и поведение реального радио на ней не воспроизводятся.
4. **Сервер выдачи конфигураций по токену не реализован** — deep link валидируется,
   но конфиг не загружается.
