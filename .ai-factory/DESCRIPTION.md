# Project: TURN Proxy Connector

## Overview
Android application that creates a local HTTP proxy server (port 8081) and tunnels all traffic through TURN relay servers to a VPS running a Go relay server, which forwards the requests to the internet. This provides a way to route traffic through TURN infrastructure typically used for WebRTC, effectively using TURN servers as proxy intermediaries.

## Core Features
- Local HTTP/HTTPS proxy server on port 8081 (supports CONNECT method for HTTPS)
- TURN server credential acquisition (VK Calls, Yandex Telemost)
- DTLS-encrypted tunnel between Android client and VPS relay server
- TURN relay allocation and traffic forwarding through relay
- Manageable list of TURN servers with add/edit/delete
- Start/Stop proxy with foreground service for persistence
- Real-time log viewer in the UI
- Configurable settings (proxy port, connection count, timeouts, etc.)

## Tech Stack
- **Language (Android):** Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Architecture:** MVVM with StateFlow
- **Networking (Android):** Ktor/OkHttp for HTTP proxy, raw UDP sockets + pion-like TURN/DTLS
- **Background Service:** Android Foreground Service
- **Persistence:** SharedPreferences / DataStore for settings, Room or JSON for server list
- **Language (Server):** Go
- **Server Libraries:** pion/turn, pion/dtls, net/http (reverse proxy)
- **Build:** Gradle (Android), Go modules (Server)

## Architecture Notes
- The Android app acts as a local HTTP proxy. Incoming HTTP requests are parsed, forwarded via a TURN relay channel to the VPS server, which makes the actual HTTP request and returns the response.
- TURN servers provide the relay layer. The client allocates a relay on the TURN server pointing at the VPS peer address. Data flows: App → TURN relay → VPS → Internet.
- Optional DTLS encryption wraps the TURN traffic for additional obfuscation.
- The Go server on the VPS listens for incoming DTLS/UDP connections and forwards received data as HTTP requests to the internet, returning responses back through the same channel.
- Multiple parallel TURN connections can be established for throughput.

## Non-Functional Requirements
- Logging: Real-time logs displayed in UI, configurable log level
- Error handling: Graceful reconnection on TURN/DTLS failures
- Security: DTLS encryption, credential refresh, no plaintext credential storage
- Performance: Multiple parallel TURN allocations for bandwidth
- Battery: Foreground service with proper notification, efficient socket handling
