# TASKS.md

## Описание проекта

Android приложение поднимает локальный HTTP прокси (порт 8081) и весь трафик отправляет через TURN серверы на VPS с Go сервером, который перенаправляет трафик в интернет.

Референс: `tmp/vk-turn-proxy/client/main.go` (клиент) и `tmp/vk-turn-proxy/server/main.go` (сервер).

---

## Выполнено

### Инфраструктура
- [x] Структура проекта (Android + Go server)
- [x] Контекстные файлы: AGENTS.md, DESCRIPTION.md, ARCHITECTURE.md
- [x] Корневой Makefile (сборка, установка, отладка)
- [x] server/Makefile (сборка, systemd, управление сервисом)
- [x] server/turn-proxy.service (systemd unit)
- [x] README.md (установка, make-команды)

### Go сервер (server/)
- [x] DTLS listener (pion/dtls) — принимает зашифрованные соединения
- [x] TCP listener (http-listen) — для отладки и plain TCP подключений
- [x] HTTP forward proxy: обычные HTTP запросы
- [x] CONNECT tunneling: HTTPS через tunnel
- [x] Bidirectional relay (двусторонняя передача данных)
- [x] Graceful shutdown (SIGTERM/SIGINT)
- [x] Idle timeout для соединений
- [x] Удаление hop-by-hop заголовков

### Android приложение — UI
- [x] Jetpack Compose + Material 3 тема
- [x] MainScreen с навигацией (4 вкладки)
- [x] StatusScreen — кнопка старт/стоп, индикатор статуса, инфо-карточка
- [x] ServersScreen — список TURN серверов, добавление/редактирование/удаление
- [x] LogsScreen — цветные логи с авто-скроллом, очистка
- [x] SettingsScreen — секции: прокси, relay сервер, подключение, DNS, логи, уведомления

### Android приложение — Логика
- [x] Модели данных: TurnServer, AppSettings
- [x] SettingsRepository (SharedPreferences + Gson)
- [x] LogBuffer (ring buffer + StateFlow)
- [x] MainViewModel (MVVM, StateFlow)
- [x] ProxyService (foreground service)
- [x] HttpProxyServer — локальный HTTP прокси 127.0.0.1:8081
- [x] Перенаправление HTTP и CONNECT запросов на relay сервер
- [x] Уведомления и разрешения (POST_NOTIFICATIONS)

---

## Не сделано

### КРИТИЧНОЕ — Ядро TURN туннелирования

- [x] **TURN клиент в Android** — реализовать подключение к TURN серверам
  - [x] Портировать TURN allocate/relay логику (StunMessage.kt + TurnClient.kt)
  - [x] Подключение к TURN серверу по UDP или TCP
  - [x] Получение relay allocation
  - [x] Маршрутизация трафика через TURN relay на VPS сервер
  - [ ] Поддержка нескольких одновременных TURN подключений (пул)

- [x] **DTLS шифрование (транспорт)** — обфускация трафика
  - [x] DtlsPacketTransport — UDP обёртка для DTLS
  - [x] DtlsClient — заглушка для BC DTLS (transport layer ready)
  - [ ] Реальная интеграция BC DTLSClientProtocol (full handshake)
  - [x] Переключатель DTLS on/off в настройках (уже есть в UI)

- [x] **Получение TURN credentials** — реализовать получение логина/пароля для TURN серверов
  - [x] Ручной ввод credentials (UI уже есть — username/password в TurnServer)
  - [x] Автоматическое получение VK credentials (VkCredentialFetcher)
  - [x] Автоматическое получение Yandex credentials (YandexCredentialFetcher, parsing ready, WebSocket TODO)

### Прокси

- [x] **Переработка HttpProxyServer** — заменить plain TCP подключение на TURN tunnel
  - [x] Абстракция транспорта: TunnelConnection interface (PlainTcpTunnel / TurnTunnel)
  - [x] TunnelConnectionFactory — выбор транспорта по конфигурации
  - [x] Пул соединений через TURN (TunnelPool)
  - [x] Автопереподключение при обрыве (retry в TunnelPool)
  - [ ] Переключение между TURN серверами при отказе (failover по списку)

### Сервер

- [ ] PSK/токен аутентификация клиентов на VPS сервере
- [ ] Rate limiting
- [ ] Метрики и статистика (кол-во подключений, трафик)
- [ ] Логирование в файл

### UI/UX улучшения

- [x] Статистика трафика на StatusScreen (байт отправлено/получено)
- [ ] Индикатор качества соединения / пинг до VPS
- [ ] Экспорт/импорт конфигурации (список серверов + настройки)
- [ ] Темная/светлая тема (переключатель)
- [ ] Автозапуск прокси при старте приложения (опция)
- [ ] Виджет для быстрого старт/стопа

### Стабильность

- [x] Обработка сетевых ошибок и retry в TURN клиенте (TunnelPool maxRetries)
- [ ] Watchdog для переподключения при обрыве TURN сессии
- [x] Unit-тесты для STUN/TURN клиента (26 + 8 + 4 + 7 + 10 + 20 = 75 тестов)
- [ ] Интеграционный тест: прокси → TURN → VPS → интернет

---

## Порядок реализации (первая итерация)

| # | Задача | Приоритет | Статус |
|---|--------|-----------|--------|
| 1 | TURN клиент (allocate + relay) | Критичный | ✅ Готово |
| 2 | DTLS клиент (транспорт) | Критичный | ✅ Готово (transport layer) |
| 3 | Абстракция транспорта в HttpProxyServer | Критичный | ✅ Готово |
| 4 | Пул TURN соединений | Высокий | ✅ Готово |
| 5 | Автопереподключение и failover | Высокий | ✅ Готово (retry) |
| 6 | Статистика трафика | Средний | ✅ Готово |
| 7 | Автополучение VK credentials | Средний | ✅ Готово |
| 8 | Автополучение Yandex credentials | Средний | ✅ Готово (parsing, WebSocket TODO) |

---

## Аудит: Найденные проблемы и план исправлений

### Фаза 1 — Исправление багов в существующем коде

| # | Баг | Серьёзность | Описание | Файлы |
|---|-----|-------------|----------|-------|
| C3 | TCP STUN framing | CRITICAL | `sendRaw()` добавляет 2-байтовый length prefix. RFC 5766 over TCP использует self-framing (STUN 20-byte header / ChannelData 4-byte header). TURN серверы не распарсят. | TurnClient.kt |
| C5 | Plain HTTP зависает | CRITICAL | `pipeStream()` ждёт EOF, но Go сервер держит keep-alive. Прокси зависнет навсегда для обычных HTTP запросов. Нужно парсить HTTP response и форвардить по Content-Length/chunked. | HttpProxyServer.kt |
| H3 | Pipe threads не прерываются | HIGH | Когда одна сторона закрывается, вторая сторона висит 30с на `receiveChannelData(30000)`. Нужно закрывать сокеты. | HttpProxyServer.kt |
| H4 | `useDtls` игнорируется | HIGH | `TunnelConnectionFactory.create()` всегда создаёт plain `TurnTunnel`, никогда не использует DTLS. | TunnelConnection.kt |
| H5 | Channel filtering отсутствует | HIGH | `RelayInputStream` берёт любой ChannelData из общей очереди, не проверяя `channelNumber`. | TurnClient.kt |
| M1 | Нет chunked Transfer-Encoding | MEDIUM | Только Content-Length тела пересылаются. Chunked (частый в HTTP/1.1) молча теряется. | HttpProxyServer.kt |

### Фаза 2 — Реализация DTLS (Bouncy Castle)

Go сервер требует DTLS handshake. Без реального DTLS приложение через TURN работать не может.
Текущий `DtlsClient.kt` — заглушка (plain `DatagramSocket` без шифрования).

| # | Задача | Описание |
|---|--------|----------|
| D1 | `TurnDatagramTransport` | Реализовать BC `DatagramTransport` поверх `TurnClient.sendChannelData()`/`receiveChannelData()` |
| D2 | `InsecureDtlsTlsClient` | BC `DefaultTlsClient` с `ServerOnlyTlsAuthentication` (skip verify), cipher `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` |
| D3 | Переписать `DtlsClient.kt` | Использовать `DTLSClientProtocol.connect()` для реального DTLS handshake через TURN relay |
| D4 | `DtlsTunnelConnection` | `TunnelConnection` = TurnClient + DTLS → `DTLSTransport` → InputStream/OutputStream |
| D5 | Обновить `TunnelConnectionFactory` | При `useDtls=true` создавать `DtlsTunnelConnection` вместо `TurnTunnel` |
| D6 | Unit-тесты для DTLS | Тесты на `TurnDatagramTransport`, mock DTLS handshake |

### Фаза 3 — Пул соединений и надёжность

| # | Задача | Серьёзность | Описание |
|---|--------|-------------|----------|
| C4 | Реальный пул соединений | CRITICAL | Текущий `TunnelPool` — семафор-limited фабрика (новый TURNClient на каждый запрос, 4+ round-trip). Нужно переиспользовать DTLS+TURN сессии. |
| H1 | TURN Refresh | HIGH | Аллокации истекают за ~600с. Нужен таймер Refresh каждые ~300с. |
| H2 | 438 Stale Nonce | HIGH | Если nonce протух, `createPermission`/`channelBind` молча фейлятся. Нужен retry с новым nonce. |

### Порядок исправлений

| # | Задача | Приоритет | Статус |
|---|--------|-----------|--------|
| 1 | C3: TCP STUN framing | Критичный | ✅ |
| 2 | C5 + M1: Plain HTTP forwarding + chunked | Критичный | ✅ |
| 3 | H3 + H5: Pipe threads + channel filter | Высокий | ✅ |
| 4 | H4: Wire useDtls | Высокий | ✅ |
| 5 | D1-D3: DTLS transport layer | Критичный | ✅ |
| 6 | D4-D5: DtlsTunnelConnection + factory | Критичный | ✅ |
| 7 | C4: Реальный пул соединений | Критичный | ✅ |
| 8 | H1: TURN Refresh | Высокий | ✅ |
| 9 | H2: 438 Stale Nonce | Высокий | ✅ |
| 10 | D6: Unit-тесты DTLS | Средний | ✅ (78 тестов) |
| 11 | Интеграционные тесты | Низкий | ⏳ |

---

## Аудит #2: Найденные проблемы

### Критические

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| C1 | `TurnDatagramTransport.receive()` возвращает -1 | BC `DatagramTransport.receive()` контракт: >= 0 или IOException. Возврат -1 сломает DTLS handshake | TurnDatagramTransport.kt |
| C2 | Shared `channelDataQueue` конкуренция | `TurnDatagramTransport` и `RelayInputStream` читают из одной очереди — данные теряются при нескольких каналах | TurnClient.kt |

### Высокие

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| H1 | Нет переиспользования TURN+DTLS | Каждый HTTP запрос = новый TURN allocate + DTLS handshake (1-5 сек). Plain HTTP будет нереально медленный | TunnelPool.kt |
| H2 | `YandexCredentialFetcher` всегда null | WebSocket шаг не реализован — `return null` | YandexCredentialFetcher.kt |
| H3 | Нет watchdog/auto-reconnect | При сбое TURN/DTLS — прокси молча умирает | - |

### Средние

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| M1 | `TurnDatagramTransport.close()` не разблокирует | Потоки ждут `poll()` до 30с после close() | TurnDatagramTransport.kt |
| M2 | `DtlsOutputStream.maxChunk=1100` > getSendLimit | Рассинхрон MTU: transport limit=1200, DTLS overhead ~80 байт → пакеты могут не влезть | DtlsClient.kt, TurnDatagramTransport.kt |
| M3 | `RelayInputStream` infinite loop на пустом ChannelData | data.size=0 → offset >= size → poll снова → бесконечный loop | TurnClient.kt |
| M4 | Нет DTLS Connection ID (RFC 9146) | Go сервер использует `RandomCIDGenerator(8)`, клиент не посылает CID — скорее всего ОК, но не проверено | DtlsClient.kt |
| M5 | Dead code в `encodeWithIntegrity` | `attrBuf` выделяется, заполняется, но не используется | StunMessage.kt |

### Порядок исправлений (Аудит #2)

| # | Задача | Приоритет | Статус |
|---|--------|-----------|--------|
| 1 | C1: TurnDatagramTransport бросать IOException при timeout | Критичный | ✅ |
| 2 | M2: Согласовать MTU — getSendLimit и maxChunk | Средний | ✅ |
| 3 | M3: Защита от пустого ChannelData | Средний | ✅ |
| 4 | M5: Удалить dead code в encodeWithIntegrity | Средний | ✅ |
| 5 | H1: Переиспользование соединений для plain HTTP | Высокий | ✅ |
| 6 | Build + тесты (78/78 pass) | — | ✅ |

---

## Аудит #3: Полный аудит

**Дата**: 2026-03-15  
**Контекст**: Прочитаны ВСЕ исходные файлы приложения, Go сервера, референсного клиента.

### Вердикт: Условно ДА — приложение может работать, но с ограничениями

#### Что БУДЕТ работать:
- ✅ **HTTPS (CONNECT)** через DTLS+TURN → VPS → интернет (но с 30-секундным idle timeout!)
- ✅ **Plain HTTP** через DTLS+TURN с переиспользованием соединений (pool reuse)
- ✅ **VK credentials** — автоматическое получение (полная реализация)
- ✅ **Ручная настройка** TURN серверов
- ✅ **DTLS handshake** — BC `DTLSClientProtocol` ↔ pion/dtls на Go сервере совместимы:
  - Cipher: `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` ✅ (совпадает)
  - Extended Master Secret: BC по умолчанию предлагает, Go требует ✅
  - CID: Go `RandomCIDGenerator(8)` — опционально; BC не предлагает CID → сервер не использует ✅
  - Client cert: Go `NoClientCert` (по умолчанию) → BC не нужно предъявлять сертификат ✅
  - `InsecureSkipVerify`: BC `ServerOnlyTlsAuthentication.notifyServerCertificate()` ничего не проверяет ✅
- ✅ **Протокол TURN** — allocate, createPermission, channelBind, refresh, 438 stale nonce
- ✅ **TCP/UDP TURN** с корректным self-framing (RFC 5766 §7)
- ✅ **Go сервер** — DTLS listener + HTTP forward proxy + CONNECT tunneling

#### Что НЕ будет работать нормально:
- ❌ **HTTPS browsing** — CONNECT туннели рвутся через 30 секунд простоя
- ❌ **Yandex credentials** — WebSocket шаг не реализован
- ❌ **Отладка** — ошибки DTLS/TURN проглатываются без логирования

---

### Найденные проблемы

### Высокие

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| H1 | **DtlsInputStream: 30-секундный hardcoded timeout** | `transport.receive(tmp, 0, tmp.size, 30000)` — если данные не поступают 30 сек, стрим возвращает EOF (-1). Для CONNECT туннелей (HTTPS) это катастрофа: пользователь перестаёт кликать на 30 сек → туннель рвётся. Go сервер держит idle timeout 30 минут, но клиент рвёт через 30 сек. | DtlsClient.kt:109 |
| H2 | **RelayInputStream: аналогичный 30-сек timeout** | `receiveForChannelNonEmpty(30000)` — та же проблема для non-DTLS режима (TurnTunnel). Idle connection рвётся через 30 сек. | TurnClient.kt:470,479 |
| H3 | **Ошибки DTLS/TURN проглатываются** | `DtlsClient.connect()` → `catch (e: Exception) { null }`. `DtlsTurnTunnel.connect()` → `catch (e: Exception) { null }`. Никакого логирования. Невозможно отладить, почему не работает. | DtlsClient.kt:76-80, TunnelConnection.kt:175-180 |
| H4 | **Pool warmUp не вызывается** | `TunnelPool.warmUp()` существует, но никогда не вызывается. Первый запрос блокируется на 1-5 сек (TURN allocate + DTLS handshake). | HttpProxyServer.kt:64-76 |

### Средние

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| M1 | **Нет health-check при возврате в pool** | `release()` → `idleConnections.offer(inner)` — без проверки живости. Stale DTLS-соединение может быть передано следующему запросу → ошибка. | TunnelPool.kt:88-92 |
| M2 | **HTTP/1.0 response без Content-Length зависнет** | Код попадает в `pipeStream()` → ждёт EOF, но keep-alive соединение не закрывается. На практике Go `resp.Write()` всегда добавляет Content-Length или chunked, но edge case опасен. | HttpProxyServer.kt:335-338 |
| M3 | **YandexCredentialFetcher всё ещё null** | WebSocket шаг не реализован — `return null`. VK работает, Yandex нет. | YandexCredentialFetcher.kt:210 |
| M4 | **Нет watchdog/auto-reconnect** | При смерти TURN сессии прокси молча перестаёт работать. Нет мониторинга и переподключения. | — |
| M5 | **Go сервер: новый http.Transport на каждый запрос** | `handleHTTPRequest()` создаёт `&http.Transport{...}` + `defer transport.CloseIdleConnections()`. Нет переиспользования TCP-соединений к целевым серверам. Неэффективно, но работает. | server/main.go:252-268 |
| M6 | **Connection: close не проверяется при release** | Если Go сервер отправит `Connection: close`, Android-клиент всё равно вернёт соединение в pool. Следующий запрос сломается. | HttpProxyServer.kt:340 |

### Низкие

| # | Баг | Описание | Файлы |
|---|-----|----------|-------|
| L1 | **RelayOutputStream maxChunk=1200 hardcoded** | Для TCP TURN можно больше. Для UDP — ОК. | TurnClient.kt:512 |
| L2 | **BC DTLS retransmission timing** | Начальный таймер BC ~1 сек. Через TURN relay RTT может быть >1с → лишние ретрансмиссии. Handshake всё равно пройдёт, но медленнее. | — |

### Анализ end-to-end потока данных

```
Browser → 127.0.0.1:8081 (HttpProxyServer)
  → TunnelPool.acquire() → DtlsTurnTunnel.connect()
    → TurnClient.connect() [UDP/TCP к TURN серверу]
    → TurnClient.allocate() [401→credentials→allocate]
    → TurnClient.createPermission(vpsAddr)
    → TurnClient.channelBind(vpsAddr) → channel 0x4000
    → TurnDatagramTransport(turnClient, channel, mtu=1200)
    → DtlsClient.connect(transport) → DTLSClientProtocol.connect()
      → BC handshake: ClientHello → [ChannelData] → TURN → VPS
      → ServerHello+Certificate+Finished ← [ChannelData] ← TURN ← VPS
    → DTLSTransport → DtlsInputStream + DtlsOutputStream
  → Forward HTTP request → DTLS encrypt → ChannelData → TURN relay → VPS
  → VPS Go server: http.ReadRequest → proxy/CONNECT → response
  → Response ← DTLS decrypt ← ChannelData ← TURN relay ← VPS
  → Forward to browser
```

### Thread-safety анализ
- `TurnClient.sendRaw()`: UDP `DatagramSocket.send()` — thread-safe; TCP `synchronized(tcpOut)` ✅
- `channelDataQueue`: `LinkedBlockingQueue` ✅
- `pendingResponses`: `ConcurrentHashMap` ✅
- `PooledStream.disposed`: `AtomicBoolean` ✅
- BC `DTLSTransport`: send/receive на разных потоках — ОК для CONNECT ✅
- TURN refresh timer: отдельный daemon thread, не конфликтует ✅

### Порядок исправлений (Аудит #3)

| # | Задача | Приоритет | Статус |
|---|--------|-----------|--------|
| 1 | H1: DtlsInputStream timeout → 30 мин (параметр idleTimeoutMs) | Высокий | ✅ |
| 2 | H2: RelayInputStream timeout → 30 мин (параметр idleTimeoutMs) | Высокий | ✅ |
| 3 | H3: Логирование ошибок DTLS/TURN (DtlsClient, TurnTunnel, DtlsTurnTunnel) | Высокий | ✅ |
| 4 | H4: Вызвать pool.warmUp(1) при старте (async, в Dispatchers.IO) | Высокий | ✅ |
| 5 | M1: Health-check при возврате в pool (inputStream.available()) | Средний | ✅ |
| 6 | M6: Проверка Connection: close при release | Средний | ✅ |
| 7 | Build + тесты (78/78 pass) | — | ✅ |

---

## Аудит #4 — Полный аудит

**Дата:** Аудит после исправления всех задач аудитов #1, #2, #3.
**Тесты:** 78/78 pass, 0 failures. BUILD SUCCESSFUL.
**Файлы проверены:** StunMessage.kt, ChannelData.kt, TurnClient.kt, TurnDatagramTransport.kt, DtlsClient.kt, TunnelConnection.kt, TunnelPool.kt, HttpProxyServer.kt, ProxyService.kt, AppSettings.kt, VkCredentialFetcher.kt, YandexCredentialFetcher.kt, server/main.go, все 7 тест-файлов.

### Вердикт: Условно ДА — приложение будет работать ~10 минут

Приложение **будет работать** для коротких сессий (< 10 минут). Для длительных соединений (HTTPS стриминг, WebSocket, долгие загрузки) **сломается через ~10 минут** из-за критической проблемы C1.

#### Что работает правильно:
- ✅ STUN/TURN протокол: encode/decode, 401 retry, 438 stale nonce retry
- ✅ ChannelData framing (RFC 5766 §11.4)
- ✅ TCP self-framing (STUN + ChannelData) 
- ✅ DTLS handshake через BC DTLSClientProtocol (cipher suite + ExtendedMasterSecret совпадают с Go сервером)
- ✅ DtlsInputStream/DtlsOutputStream с буферизацией
- ✅ RelayStream с буферизацией и фильтрацией по каналу
- ✅ TunnelPool с acquire/release/close, semaphore, retry
- ✅ HttpProxyServer: CONNECT + plain HTTP + chunked encoding
- ✅ Go сервер: DTLS listener + TCP listener, HTTP proxy + CONNECT
- ✅ VkCredentialFetcher: полный flow (6 шагов API)
- ✅ Thread-safety: ConcurrentHashMap, LinkedBlockingQueue, AtomicBoolean, synchronized
- ✅ Сервис (foreground) + UI + настройки

### Найденные проблемы

#### CRITICAL

| # | Проблема | Описание |
|---|----------|----------|
| C1 | **ChannelBind не обновляется** | RFC 5766 §11.3: "A channel binding lasts for 10 minutes unless refreshed". Код обновляет только allocation (`client.refresh(600)` каждые 300с), но **не отправляет повторный ChannelBind** для обновления привязки канала. Через 10 минут TURN сервер перестаёт ретранслировать ChannelData → все туннели ломаются. Это касается и TurnTunnel, и DtlsTurnTunnel. Также CreatePermission (5 мин) неявно обновляется через ChannelBind, поэтому без обновления ChannelBind разрешение тоже истекает. |

#### HIGH

| # | Проблема | Описание |
|---|----------|----------|
| H1 | **Content-Length Long→Int усечение** | `HttpProxyServer.handleClient()`: `pipeBytes(relayIn, clientOut, respContentLength.toInt())` — `respContentLength` это `Long`, но `pipeBytes()` принимает `Int`. Для ответов > 2GB происходит overflow → скачивание обрезается или 0 байт. |
| H2 | **Health-check пула — no-op** | `TunnelPool.PooledStream.release()` вызывает `inputStream.available()`. Но ни `DtlsInputStream`, ни `RelayInputStream` не переопределяют `available()` — базовый `InputStream.available()` всегда возвращает 0. `0 >= 0` = true → проверка всегда проходит. Мёртвые соединения возвращаются в пул. |

#### MEDIUM

| # | Проблема | Описание |
|---|----------|----------|
| M1 | **Dead code в DtlsTurnTunnel** | Переменная `idleMs` вычисляется, но не используется — `DtlsInputStream` создаётся с хардкодом `30 * 60 * 1000`. |
| M2 | **Go сервер: новый http.Transport на каждый запрос** | `handleHTTPRequest()` создаёт `&http.Transport{}` на каждый plain HTTP запрос. Это мешает переиспользованию TCP соединений к upstream серверам. |
| M3 | **YandexCredentialFetcher возвращает null** | `fetchCredentials()` возвращает `null` — WebSocket шаг не реализован (`// TODO: Add OkHttp WebSocket integration`). |
| M4 | **Нет watchdog/auto-reconnect** | При обрыве TURN/DTLS соединения нет автоматического переподключения. Пул создаст новое соединение только при следующем `acquire()`. |
| M5 | **HTTP/1.0 ответы без Content-Length → deadlock** | Если Go сервер проксирует HTTP/1.0 ответ без Content-Length и без chunked, Kotlin прокси вызывает `pipeStream()` (читает до EOF). Но Go сервер не закрывает соединение (keep-alive loop) → deadlock. Крайне редкий случай (Go `resp.Write()` обычно добавляет chunked для HTTP/1.1). |
| M6 | **DtlsInputStream может вернуть 0 из read()** | Если DTLSTransport.receive() вернёт 0-byte запись, `read(b, off, len)` вернёт 0 при `len > 0` — нарушение контракта InputStream. На практике невозможно (DTLS записи > 0), но формально баг. |

### Архитектурная схема (End-to-End flow)

```
Browser → HTTP/HTTPS request
    ↓
HttpProxyServer (127.0.0.1:8081)
    ↓ parse request + headers
TunnelPool.acquire()
    ↓ idle connection or new DtlsTurnTunnel  
DtlsTurnTunnel.connect()
    ↓ TurnClient → Allocate (401 retry) → CreatePermission → ChannelBind
    ↓ TurnDatagramTransport (ChannelData over TURN)
    ↓ DtlsClient → BC DTLS 1.2 handshake (ECDHE_ECDSA_AES_128_GCM)
    ↓ DtlsInputStream / DtlsOutputStream  
    ↓                                        ↑ ChannelBind expires @ 10 min! ❌
Forward request → DTLS encrypt → ChannelData → TURN relay → VPS
    ↓
Go Server (VPS :56000)
    ↓ DTLS Accept + Handshake → handleProxyConnection()
    ↓ http.ReadRequest → CONNECT (dial target) or HTTP (http.Transport.RoundTrip)
    ↓ Response
    ↓
Response ← DTLS decrypt ← ChannelData ← TURN relay ← VPS
    ↓
Browser ← HTTP response
```

### Порядок исправлений (Аудит #4)

| # | Задача | Приоритет | Статус |
|---|--------|-----------|--------|
| 1 | C1: Добавить периодический ChannelBind refresh (каждые 300с) в TurnTunnel и DtlsTurnTunnel | Критический | ✅ |
| 2 | H1: Исправить pipeBytes() — принимать Long вместо Int | Высокий | ✅ |
| 3 | H2: Реализовать реальный health-check (flush outputStream) | Высокий | ✅ |
| 4 | M1: Удалить dead code idleMs в DtlsTurnTunnel | Средний | ✅ |
| 5 | M2: Вынести http.Transport в глобальную переменную в Go сервере | Средний | ✅ |
| 6 | M5: Обработать HTTP/1.0 edge case (close + return после pipeStream) | Средний | ✅ |
| 7 | M6: Проверка n <= 0 в DtlsInputStream.read() | Средний | ✅ |
| 8 | Build + тесты (78/78 pass) + Go build OK | — | ✅ |
