.PHONY: help build run clean install uninstall status logs restart stop

SHELL      := /bin/bash
BIN        := turn-proxy-server
SERVICE    := turn-proxy
INSTALL_DIR := /usr/local/bin
SYSTEMD_DIR := /etc/systemd/system

# Default server flags (override via env or make args)
DTLS_LISTEN  ?= 0.0.0.0:56000
HTTP_LISTEN  ?= 0.0.0.0:8080
TIMEOUT      ?= 30m

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ============ Build ============

build: ## Build server binary
	go build -o $(BIN) .

build-linux: ## Cross-compile for Linux amd64
	GOOS=linux GOARCH=amd64 go build -o $(BIN) .

run: build ## Build and run server locally
	./$(BIN) --listen $(DTLS_LISTEN) --http-listen $(HTTP_LISTEN) --timeout $(TIMEOUT)

clean: ## Remove build artifacts
	rm -f $(BIN)

# ============ Systemd Service ============

install: build ## Install binary and systemd service
	@echo "Installing $(BIN) to $(INSTALL_DIR)..."
	sudo cp $(BIN) $(INSTALL_DIR)/$(BIN)
	sudo chmod +x $(INSTALL_DIR)/$(BIN)
	@echo "Installing systemd unit $(SERVICE).service..."
	sudo cp $(SERVICE).service $(SYSTEMD_DIR)/$(SERVICE).service
	sudo systemctl daemon-reload
	sudo systemctl enable $(SERVICE)
	sudo systemctl restart $(SERVICE)
	@echo "Done. Service $(SERVICE) is active."
	@sudo systemctl status $(SERVICE) --no-pager || true

uninstall: stop ## Uninstall binary and systemd service
	sudo systemctl disable $(SERVICE) || true
	sudo rm -f $(SYSTEMD_DIR)/$(SERVICE).service
	sudo rm -f $(INSTALL_DIR)/$(BIN)
	sudo systemctl daemon-reload
	@echo "Service $(SERVICE) uninstalled."

# ============ Service Management ============

status: ## Show service status
	sudo systemctl status $(SERVICE) --no-pager

logs: ## Show service logs (last 100 lines, follow)
	sudo journalctl -u $(SERVICE) -n 100 -f

restart: ## Restart service
	sudo systemctl restart $(SERVICE)

stop: ## Stop service
	sudo systemctl stop $(SERVICE)

start: ## Start service
	sudo systemctl start $(SERVICE)

# ============ Dependencies ============

deps: ## Download Go dependencies
	go mod tidy
	go mod download

test: ## Run tests
	go test ./...
