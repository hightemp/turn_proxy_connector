package com.hightemp.turn_proxy_connector.data

data class AppSettings(
    val proxyPort: Int = 8081,
    val serverHost: String = "",
    val serverPort: Int = 56000,
    val connectionCount: Int = 1,
    val connectionTimeoutSec: Int = 30,
    val idleTimeoutMin: Int = 30,
    val useDtls: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxLogLines: Int = 500,
    val dnsServer: String = "",
    val showNotifications: Boolean = true
)
