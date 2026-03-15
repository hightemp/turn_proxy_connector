package com.hightemp.turn_proxy_connector.data

import java.util.UUID

data class TurnServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 3478,
    val username: String = "",
    val password: String = "",
    val useTcp: Boolean = false,
    val enabled: Boolean = true
)
