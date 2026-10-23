# Сборка и установка

## Требования

| Компонент | Версия | Проверено |
| --- | --- | --- |
| JDK | 21 | 21.0.12.1 (Debian) |
| Android SDK Platform | 36 | ✅ |
| Build-Tools | 36.0.0 | ✅ |
| NDK | 26.1.10909125 | ✅ (только для сборки AAR движка) |
| CMake | ≥ 3.22 | 3.22.1 (SDK) / 3.31.6 (система) |
| Gradle | 8.13 | wrapper |
| AGP | 8.13.0 | ✅ |
| Kotlin | 2.2.20 | ✅ |
| Go | 1.24.2+ | нужен только для WDTT-клиента (не используется) |

`minSdk 26`, `targetSdk 36`.

## Сборка AAR движка (один раз)

Готового AAR AmneziaWG в Maven Central нет (`org/amnezia/` → 404). Собираем сами:

```bash
git clone --depth 1 --recurse-submodules --shallow-submodules \
  https://github.com/amnezia-vpn/amneziawg-android.git
cd amneziawg-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :tunnel:assembleRelease
# → tunnel/build/outputs/aar/tunnel-release.aar  (6.0 МБ)
cp tunnel/build/outputs/aar/tunnel-release.aar <project>/app/libs/awg-tunnel.aar
```

Проверка артефакта:

```bash
unzip -l tunnel-release.aar | grep -E "jni|classes"
# jni/arm64-v8a/libwg-go.so, libwg.so, libwg-quick.so
# jni/armeabi-v7a/... , jni/x86/... , jni/x86_64/...
```

Сборка занимает несколько минут: NDK кросс-компилирует Go под 4 ABI.

## Сборка приложения

```bash
cd vpn-mvp
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk` (~30 МБ).

Приложение весит много из-за native-библиотек движка под 4 ABI. Для уменьшения
размера можно оставить только нужные ABI:

```kotlin
// app/build.gradle.kts
android {
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a") } }
}
```

## Установка

```bash
adb connect <device-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.qwer.app.debug/com.qwer.app.ui.MainActivity
```

Разрешение VPN выдаётся системным диалогом при первом подключении. Для
автоматизированных тестов:

```bash
adb shell appops set com.qwer.app.debug ACTIVATE_VPN allow
```

## Debug-хуки (только debug-сборка)

Скомпилированы вне release через `BuildConfig.DEBUG`:

```bash
# Импорт тестового конфига (base64, чтобы пережить границу аргумента adb shell)
B64=$(base64 -w0 test-wg.conf)
adb shell am start -n com.qwer.app.debug/com.qwer.app.ui.MainActivity \
  --es debug_wg_config "b64:$B64"

# Добавить резервный WDTT-профиль
adb shell am start -n com.qwer.app.debug/com.qwer.app.ui.MainActivity \
  --ez debug_wdtt_profile true
```

⚠️ Аргумент `am start` разбивается шеллом по переводам строк — поэтому base64,
а не сырой текст. Сырой многострочный конфиг придёт обрезанным и будет (правильно)
отклонён валидатором.

## Смоук-проверка туннеля

```bash
# на устройстве
adb shell ip -4 addr show | grep -A1 tun
# ожидаем: tun0 ... inet 10.99.0.2/32

# на сервере
wg show
# ожидаем: latest handshake: N seconds ago
#          transfer: X KiB received, Y KiB sent
```

`latest handshake` на сервере — единственное надёжное доказательство, что
трафик реально идёт через туннель, а не просто поднят интерфейс.
