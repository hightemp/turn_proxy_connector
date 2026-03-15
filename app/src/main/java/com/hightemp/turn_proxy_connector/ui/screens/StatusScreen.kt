package com.hightemp.turn_proxy_connector.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hightemp.turn_proxy_connector.service.ProxyState
import com.hightemp.turn_proxy_connector.ui.viewmodels.MainViewModel

@Composable
fun StatusScreen(viewModel: MainViewModel) {
    val proxyState by viewModel.proxyState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeConns by viewModel.activeConnections.collectAsState()
    val bytesSent by viewModel.bytesSent.collectAsState()
    val bytesReceived by viewModel.bytesReceived.collectAsState()

    val statusColor by animateColorAsState(
        targetValue = when (proxyState) {
            ProxyState.RUNNING -> Color(0xFF4CAF50)
            ProxyState.STARTING, ProxyState.STOPPING -> Color(0xFFFFA000)
            ProxyState.STOPPED -> Color(0xFFF44336)
        },
        label = "statusColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Status indicator
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = statusColor
        ) {}

        Text(
            text = when (proxyState) {
                ProxyState.RUNNING -> "RUNNING"
                ProxyState.STARTING -> "STARTING..."
                ProxyState.STOPPING -> "STOPPING..."
                ProxyState.STOPPED -> "STOPPED"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )

        // Big start/stop button
        FilledIconButton(
            onClick = { viewModel.toggleProxy() },
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = when (proxyState) {
                    ProxyState.RUNNING -> MaterialTheme.colorScheme.error
                    ProxyState.STOPPED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            enabled = proxyState == ProxyState.RUNNING || proxyState == ProxyState.STOPPED
        ) {
            Icon(
                imageVector = when (proxyState) {
                    ProxyState.RUNNING -> Icons.Default.Stop
                    else -> Icons.Default.PlayArrow
                },
                contentDescription = if (proxyState == ProxyState.RUNNING) "Stop" else "Start",
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info cards
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Connection Info",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("Proxy Address", "127.0.0.1:${settings.proxyPort}")
                InfoRow("Relay Server", "${settings.serverHost.ifBlank { "Not set" }}:${settings.serverPort}")
                InfoRow("Active Connections", "$activeConns")
                InfoRow("DTLS", if (settings.useDtls) "Enabled" else "Disabled")
                InfoRow("Pool Size", "${settings.turnPoolSize}")
            }
        }

        // Traffic stats card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Traffic",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("Sent", formatBytes(bytesSent))
                InfoRow("Received", formatBytes(bytesReceived))
                InfoRow("Total", formatBytes(bytesSent + bytesReceived))
            }
        }

        if (settings.serverHost.isBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "⚠ Please configure the relay server address in Settings before starting the proxy.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
