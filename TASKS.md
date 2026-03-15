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

## Порядок реализации

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
| 9 | Аутентификация клиентов на сервере | Низкий | ⏳ |
| 10 | Реальный DTLS handshake (BC) | Низкий | ⏳ |
| 11 | Интеграционные тесты | Низкий | ⏳ |
