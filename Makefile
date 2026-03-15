.PHONY: help build-server build-app build-all install-server \
       debug-app debug-app-release clean clean-server clean-app \
       run-server lint-app test-app

SHELL := /bin/bash

# --- Config ---
APP_ID          := com.hightemp.turn_proxy_connector
SERVER_DIR      := server
SERVER_BIN      := turn-proxy-server
GRADLE          := ./gradlew

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ============ Build ============

build-server: ## Build Go relay server binary
	cd $(SERVER_DIR) && go build -o $(SERVER_BIN) .

build-app: ## Build Android debug APK
	$(GRADLE) assembleDebug

build-app-release: ## Build Android release APK
	$(GRADLE) assembleRelease

build-all: build-server build-app ## Build both server and Android app

# ============ Install / Deploy ============

install-app: build-app ## Install debug APK on connected device via adb
	adb install -r app/build/outputs/apk/debug/app-debug.apk

install-app-release: build-app-release ## Install release APK on connected device
	adb install -r app/build/outputs/apk/release/app-release-unsigned.apk

install-server: ## Install server on remote VPS (set VPS=user@host)
ifndef VPS
	$(error VPS is not set. Usage: make install-server VPS=user@host)
endif
	cd $(SERVER_DIR) && GOOS=linux GOARCH=amd64 go build -o $(SERVER_BIN) .
	scp $(SERVER_DIR)/$(SERVER_BIN) $(VPS):/usr/local/bin/$(SERVER_BIN)
	scp $(SERVER_DIR)/turn-proxy.service $(VPS):/etc/systemd/system/turn-proxy.service
	ssh $(VPS) 'systemctl daemon-reload && systemctl enable turn-proxy && systemctl restart turn-proxy'
	@echo "Server deployed and started on $(VPS)"

# ============ Debug ============

debug-app: install-app ## Install debug APK and attach logcat
	adb shell am start -n $(APP_ID)/.MainActivity
	adb logcat -s TurnProxy:* ProxyService:* HttpProxy:* *:E

debug-logcat: ## Show filtered logcat for the app
	adb logcat -s TurnProxy:* ProxyService:* HttpProxy:* *:E

debug-server: build-server ## Run server locally with verbose output
	cd $(SERVER_DIR) && ./$(SERVER_BIN) --listen 0.0.0.0:56000 --http-listen 0.0.0.0:8080

debug-proxy-test: ## Test local proxy with curl (app must be running)
	curl -x http://127.0.0.1:8081 http://httpbin.org/ip

# ============ Test / Lint ============

test-app: ## Run Android unit tests
	$(GRADLE) test

lint-app: ## Run Android lint
	$(GRADLE) lint

test-server: ## Run Go server tests
	cd $(SERVER_DIR) && go test ./...

# ============ Clean ============

clean-server: ## Clean server build artifacts
	cd $(SERVER_DIR) && rm -f $(SERVER_BIN)

clean-app: ## Clean Android build
	$(GRADLE) clean

clean: clean-server clean-app ## Clean everything
