package com.hightemp.turn_proxy_connector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hightemp.turn_proxy_connector.data.AppSettings
import com.hightemp.turn_proxy_connector.ui.viewmodels.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Proxy settings
        SectionCard("Proxy") {
            NumberField(
                label = "Proxy Port",
                value = settings.proxyPort,
                onValueChange = { viewModel.updateSettings(settings.copy(proxyPort = it)) }
            )
        }

        // Relay server settings
        SectionCard("Relay Server") {
            OutlinedTextField(
                value = settings.serverHost,
                onValueChange = { viewModel.updateSettings(settings.copy(serverHost = it)) },
                label = { Text("Server Host") },
                placeholder = { Text("e.g. 123.45.67.89") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            NumberField(
                label = "Server Port",
                value = settings.serverPort,
                onValueChange = { viewModel.updateSettings(settings.copy(serverPort = it)) }
            )
        }

        // Connection settings
        SectionCard("Connection") {
            NumberField(
                label = "Parallel Connections",
                value = settings.connectionCount,
                onValueChange = { viewModel.updateSettings(settings.copy(connectionCount = it.coerceIn(1, 64))) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            NumberField(
                label = "Connection Timeout (sec)",
                value = settings.connectionTimeoutSec,
                onValueChange = { viewModel.updateSettings(settings.copy(connectionTimeoutSec = it.coerceIn(5, 300))) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            NumberField(
                label = "Idle Timeout (min)",
                value = settings.idleTimeoutMin,
                onValueChange = { viewModel.updateSettings(settings.copy(idleTimeoutMin = it.coerceIn(1, 1440))) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = "Use DTLS Encryption",
                checked = settings.useDtls,
                onCheckedChange = { viewModel.updateSettings(settings.copy(useDtls = it)) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = "Auto Reconnect",
                checked = settings.autoReconnect,
                onCheckedChange = { viewModel.updateSettings(settings.copy(autoReconnect = it)) }
            )
        }

        // DNS settings
        SectionCard("DNS") {
            OutlinedTextField(
                value = settings.dnsServer,
                onValueChange = { viewModel.updateSettings(settings.copy(dnsServer = it)) },
                label = { Text("Custom DNS Server") },
                placeholder = { Text("Empty = system default") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Logging settings
        SectionCard("Logging") {
            NumberField(
                label = "Max Log Lines",
                value = settings.maxLogLines,
                onValueChange = { viewModel.updateSettings(settings.copy(maxLogLines = it.coerceIn(100, 10000))) }
            )
        }

        // Notifications
        SectionCard("Notifications") {
            SwitchRow(
                label = "Show Notifications",
                checked = settings.showNotifications,
                onCheckedChange = { viewModel.updateSettings(settings.copy(showNotifications = it)) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { newVal ->
            text = newVal.filter { it.isDigit() }
            text.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
