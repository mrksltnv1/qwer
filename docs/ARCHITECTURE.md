# Архитектура

## Слои

```
UI (Compose)          MainActivity / MainScreen
      │  StateFlow<ConnectionState>
      ▼
ConnectionManager     единственный владелец туннеля, Mutex-сериализация
      ├── TunnelEngine        ── AmneziaWG GoBackend (WG + AWG)
      ├── WdttPlusAdapter     ── handover в WDTT Plus
      ├── ConnectivityProbe   ── TcpProbe, общий дедлайн 2 с
      └── FailoverController  ── чистый автомат, без Android API
      ▼
KeystoreConfigStore   AES-GCM, ключ в Android Keystore (не экспортируется)
ProfileRepository     метаданные в SharedPreferences, конфиги в Keystore
```

## Почему один движок

`com.wireguard.android:tunnel` и `org.amnezia.awg:tunnel` — форки одного проекта.
Оба содержат `libwg-go.so`, `libwg.so`, `libwg-quick.so`. Android упаковщик не
разрешает два файла с одним путём внутри APK: один из них затрёт другой, и
`System.loadLibrary("wg-go")` из второго движка загрузит чужой код.

Проверка на собранном AAR показала, что AmneziaWG-парсер принимает plain WG:

```
[Interface]                      → jc=Optional.empty
PrivateKey = ...                   s1=Optional.empty
Address = 10.66.66.2/32            h1=Optional.empty
```

То есть обфускация просто не включается, и обычный WG-профиль работает через
AmneziaWG-стек. Один движок закрывает оба протокола, класс `Protocol` остаётся
нужен только для UI и для классификации импортированного конфига.

## Состояния

`ConnectionState` — единственный источник правды для UI:

| Состояние | Когда | Допустим ли переход в Connected |
| --- | --- | --- |
| `Disconnected` | нет туннеля | нет |
| `Connecting` | движок поднимается | нет |
| `Verifying` | туннель up, проба не прошла | нет |
| `Connected` | **проба через туннель успешна** | — |
| `Degraded` | проба падает, окно отказа идёт | нет |
| `Switching` | выполняется failover | нет |
| `Failed` | терминальная ошибка с причиной | нет |

Ключевое правило: `Connected` публикуется **только** после успешной пробы. Запуск
движка сам по себе даёт `Verifying`. Это исключает ложный «Подключено», который
требовался в задании.

## Жизненный цикл

`ConnectionManager` живёт в `Application`, не в Activity. Поворот экрана или
пересоздание Activity не рвут туннель. Сам туннель держит `GoBackend$VpnService`,
который переживает Activity.

`Mutex` вокруг старта/остановки гарантирует, что ручное действие и failover не
пересекутся. Второй туннель не стартует, пока жив первый: перед стартом всегда
выполняется `stopTunnelLocked`.

## Обработка ошибок движка

`GoBackend.setState` бросает `BackendException` с причиной:
`VPN_NOT_AUTHORIZED`, `TUNNEL_MISSING_CONFIG`, `BadConfig`. `ConnectionManager`
переводит их в human-readable текст, а не показывает стектрейс.
