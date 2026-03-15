# Architecture: Layered + Service-Oriented

## Overview
The project consists of two separate applications — an Android client and a Go server — that communicate via TURN relay channels with optional DTLS encryption. Both follow a layered architecture pattern suited for their respective platforms: MVVM for Android (Compose UI) and a flat service-oriented layout for the Go server.

This architecture was chosen because both applications are relatively focused in scope (proxy tunneling), the team is small, and the primary complexity is in networking rather than business domain logic. A layered approach keeps things simple and maintainable while providing clear separation between UI, business logic, and networking concerns.

## Decision Rationale
- **Project type:** Networking utility (HTTP proxy + TURN relay)
- **Tech stack:** Kotlin/Compose (Android) + Go (Server)
- **Key factor:** Moderate complexity centered on networking, not domain logic — layers provide adequate separation without overhead

## Folder Structure

### Android App
```
app/src/main/java/com/hightemp/turn_proxy_connector/
├── MainActivity.kt                 # Compose UI host
├── App.kt                          # Application class
├── ui/                             # Presentation layer
│   ├── theme/                      # Material 3 theme
│   ├── screens/                    # Screen composables
│   │   ├── MainScreen.kt          # Tabs: Status, Servers, Logs, Settings
│   │   ├── StatusScreen.kt        # Start/stop, connection status
│   │   ├── ServersScreen.kt       # TURN server list management
│   │   ├── LogsScreen.kt          # Real-time log viewer
│   │   └── SettingsScreen.kt      # App configuration
│   └── viewmodels/                 # ViewModels (state holders)
│       └── MainViewModel.kt       # Main app state
├── service/                        # Background service layer
│   └── ProxyService.kt            # Foreground service running the proxy
├── proxy/                          # Proxy engine layer
│   ├── HttpProxyServer.kt         # Local HTTP proxy (port 8081)
│   └── TurnTunnelManager.kt       # TURN connection management
├── turn/                           # TURN/DTLS protocol layer
│   ├── TurnClient.kt              # TURN allocation & relay
│   ├── DtlsClient.kt              # DTLS encryption wrapper
│   └── CredentialProvider.kt      # VK/Yandex credential fetching
├── data/                           # Data layer
│   ├── TurnServer.kt              # Server data model
│   ├── AppSettings.kt             # Settings data model
│   └── SettingsRepository.kt      # SharedPreferences/DataStore access
└── util/                           # Utilities
    └── LogBuffer.kt               # In-memory log ring buffer
```

### Go Server
```
server/
├── main.go                         # Entry point, CLI flags, signal handling
├── go.mod                          # Go module definition
├── go.sum                          # Dependency checksums
├── proxy.go                        # HTTP forward proxy handler
├── dtls.go                         # DTLS listener & connection handler
└── relay.go                        # Data relay between DTLS conn and HTTP
```

## Dependency Rules

### Android
- ✅ `ui/screens` → `ui/viewmodels` → `service` → `proxy` → `turn` → `data`
- ✅ `ui/viewmodels` → `data` (for settings/server list)
- ❌ `turn` → `ui` (networking layer must not know about UI)
- ❌ `proxy` → `ui` (proxy engine must not know about UI)
- ❌ `data` → `service` (data layer is passive)

### Go Server
- ✅ `main.go` → `dtls.go`, `proxy.go`, `relay.go`
- ✅ `relay.go` → `proxy.go` (relay hands off data to proxy)
- ❌ `proxy.go` → `main.go` (no circular dependencies)

## Layer Communication

### Android
- **UI ↔ ViewModel:** Compose `collectAsState()` on `StateFlow`
- **ViewModel ↔ Service:** `bindService()` + bound service interface, or `Intent` commands
- **Service ↔ ProxyEngine:** Direct method calls within the same process
- **ProxyEngine ↔ TURN:** Coroutines with `Dispatchers.IO`, suspend functions
- **Logs:** `LogBuffer` singleton collects logs from all layers, ViewModel exposes as `StateFlow`

### Go Server
- **main → dtls:** Starts DTLS listener, passes accepted connections to relay handler
- **relay → proxy:** Each accepted connection spawns a goroutine that reads HTTP requests and proxies them

## Key Principles
1. **Foreground Service is mandatory** — proxy must survive activity destruction
2. **All networking on IO dispatcher** — never block the main thread
3. **State flows down, events flow up** — unidirectional data flow in Compose
4. **Graceful shutdown** — TURN allocations and DTLS connections must be properly closed
5. **Log everything meaningful** — connection events, errors, relay statistics

## Code Examples

### ViewModel StateFlow pattern (Kotlin)
```kotlin
class MainViewModel : ViewModel() {
    private val _proxyState = MutableStateFlow(ProxyState.STOPPED)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun startProxy() {
        _proxyState.value = ProxyState.STARTING
        // Bind to ProxyService
    }

    fun stopProxy() {
        _proxyState.value = ProxyState.STOPPING
        // Unbind and stop service
    }
}
```

### Go HTTP proxy handler
```go
func handleHTTPProxy(w http.ResponseWriter, r *http.Request) {
    if r.Method == http.MethodConnect {
        handleConnect(w, r) // HTTPS tunneling
        return
    }
    // Forward HTTP request
    resp, err := http.DefaultTransport.RoundTrip(r)
    if err != nil {
        http.Error(w, err.Error(), http.StatusBadGateway)
        return
    }
    defer resp.Body.Close()
    copyHeaders(w.Header(), resp.Header)
    w.WriteHeader(resp.StatusCode)
    io.Copy(w, resp.Body)
}
```

## Anti-Patterns
- ❌ Running proxy directly in Activity lifecycle (will be killed)
- ❌ Blocking main thread with socket operations
- ❌ Storing TURN credentials in plaintext SharedPreferences
- ❌ Single TURN connection bottleneck — use multiple parallel allocations
- ❌ Ignoring TURN allocation expiry — must refresh or reallocate
