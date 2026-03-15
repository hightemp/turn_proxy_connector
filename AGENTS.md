# AGENTS.md

> Project map for AI agents. Keep this file up-to-date as the project evolves.

## Project Overview
Android app that creates a local HTTP proxy (port 8081) tunneling traffic through TURN relay servers to a Go VPS server, which forwards requests to the internet.

## Tech Stack
- **Language (Android):** Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Language (Server):** Go
- **Networking:** TURN (pion/turn), DTLS (pion/dtls), HTTP proxy
- **Build:** Gradle (Android), Go modules (Server)

## Project Structure
```
turn_proxy_connector/
├── app/                          # Android application module
│   ├── build.gradle.kts          # App-level build config & dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # App manifest, permissions, services
│   │   └── java/com/hightemp/turn_proxy_connector/
│   │       ├── MainActivity.kt   # Main entry point (Compose UI host)
│   │       └── ui/theme/         # Material 3 theme (Color, Theme, Type)
│   └── src/test/                 # Unit tests
├── server/                       # Go relay server (VPS deployment)
├── tmp/vk-turn-proxy/            # Reference Go implementation
│   ├── client/main.go            # Reference client (TURN+DTLS tunneling)
│   └── server/main.go            # Reference server (DTLS listener + UDP relay)
├── gradle/libs.versions.toml     # Version catalog
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Project settings
└── .ai-factory/                  # AI context files
    ├── DESCRIPTION.md            # Project specification
    └── ARCHITECTURE.md           # Architecture decisions
```

## Key Entry Points
| File | Purpose |
|------|---------|
| app/src/main/java/.../MainActivity.kt | Android app entry point |
| server/main.go | Go relay server entry point |
| tmp/vk-turn-proxy/client/main.go | Reference TURN client implementation |
| tmp/vk-turn-proxy/server/main.go | Reference DTLS relay server |
| app/build.gradle.kts | Android dependencies and build config |

## Documentation
| Document | Path | Description |
|----------|------|-------------|
| README | README.md (TBD) | Project landing page |

## AI Context Files
| File | Purpose |
|------|---------|
| AGENTS.md | This file — project structure map |
| .ai-factory/DESCRIPTION.md | Project specification and tech stack |
| .ai-factory/ARCHITECTURE.md | Architecture decisions and guidelines |
