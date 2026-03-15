# TURN Proxy Connector

## Требования

- Android SDK (compileSdk 35), JDK 11+
- Go 1.24+
- ADB (для установки на устройство)

## Установка

### Android приложение

```bash
make build-app            # Сборка debug APK
make install-app          # Установка debug APK на устройство через adb
```

### Сервер (Go) — на VPS

```bash
cd server
make build                # Сборка бинарника
make install              # Установка бинарника + systemd сервис (sudo)
```

Или удалённый деплой из корня проекта:

```bash
make install-server VPS=user@host
```

## Make команды (корневой Makefile)

| Команда | Описание |
|---------|----------|
| `make help` | Показать справку |
| `make build-server` | Собрать Go сервер |
| `make build-app` | Собрать debug APK |
| `make build-app-release` | Собрать release APK |
| `make build-all` | Собрать сервер и приложение |
| `make install-app` | Установить debug APK через adb |
| `make install-app-release` | Установить release APK через adb |
| `make install-server VPS=user@host` | Деплой сервера на VPS |
| `make debug-app` | Установить APK и показать logcat |
| `make debug-logcat` | Показать logcat приложения |
| `make debug-server` | Запустить сервер локально |
| `make debug-proxy-test` | Тест прокси через curl |
| `make test-app` | Юнит-тесты Android |
| `make lint-app` | Lint Android |
| `make test-server` | Тесты Go сервера |
| `make clean-server` | Очистить артефакты сервера |
| `make clean-app` | Очистить сборку Android |
| `make clean` | Очистить всё |

## Make команды (server/Makefile)

| Команда | Описание |
|---------|----------|
| `make help` | Показать справку |
| `make build` | Собрать бинарник |
| `make build-linux` | Кросс-компиляция для Linux amd64 |
| `make run` | Собрать и запустить локально |
| `make clean` | Удалить артефакты |
| `make install` | Установить бинарник + systemd сервис |
| `make uninstall` | Удалить бинарник и сервис |
| `make start` | Запустить сервис |
| `make stop` | Остановить сервис |
| `make restart` | Перезапустить сервис |
| `make status` | Статус сервиса |
| `make logs` | Логи сервиса (journalctl -f) |
| `make deps` | Скачать Go зависимости |
| `make test` | Запустить тесты |
