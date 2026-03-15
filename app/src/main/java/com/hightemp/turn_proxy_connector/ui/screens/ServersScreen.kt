package com.hightemp.turn_proxy_connector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hightemp.turn_proxy_connector.data.TurnServer
import com.hightemp.turn_proxy_connector.ui.viewmodels.MainViewModel

@Composable
fun ServersScreen(viewModel: MainViewModel) {
    val servers by viewModel.servers.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<TurnServer?>(null) }
    var showFetchDialog by remember { mutableStateOf(false) }
    val fetchState by viewModel.fetchState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TURN Servers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { showFetchDialog = true }
                ) {
                    Icon(Icons.Default.CloudDownload, "Fetch credentials")
                    Spacer(Modifier.width(4.dp))
                    Text("Fetch")
                }
                FilledTonalButton(
                    onClick = {
                        editingServer = null
                        showDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, "Add server")
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No TURN servers configured.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onEdit = {
                            editingServer = server
                            showDialog = true
                        },
                        onDelete = { viewModel.deleteServer(server.id) },
                        onToggle = { enabled ->
                            viewModel.updateServer(server.copy(enabled = enabled))
                        }
                    )
                }
            }
        }
    }

    if (showDialog) {
        ServerDialog(
            server = editingServer,
            onDismiss = { showDialog = false },
            onSave = { server ->
                if (editingServer != null) {
                    viewModel.updateServer(server)
                } else {
                    viewModel.addServer(server)
                }
                showDialog = false
            }
        )
    }

    if (showFetchDialog) {
        FetchCredentialsDialog(
            fetchState = fetchState,
            onFetch = { link -> viewModel.fetchCredentials(link) },
            onDismiss = {
                showFetchDialog = false
                viewModel.resetFetchState()
            }
        )
    }
}

@Composable
private fun ServerCard(
    server: TurnServer,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (server.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.name.ifBlank { "Unnamed Server" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (server.username.isNotBlank()) {
                        Text(
                            "User: ${server.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (server.useTcp) "TCP" else "UDP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = onToggle
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, "Edit")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Server") },
            text = { Text("Delete '${server.name.ifBlank { server.host }}'?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ServerDialog(
    server: TurnServer?,
    onDismiss: () -> Unit,
    onSave: (TurnServer) -> Unit
) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "3478") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var useTcp by remember { mutableStateOf(server?.useTcp ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server != null) "Edit Server" else "Add Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use TCP")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = useTcp, onCheckedChange = { useTcp = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = (server ?: TurnServer()).copy(
                        name = name,
                        host = host,
                        port = port.toIntOrNull() ?: 3478,
                        username = username,
                        password = password,
                        useTcp = useTcp
                    )
                    onSave(s)
                },
                enabled = host.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FetchCredentialsDialog(
    fetchState: MainViewModel.FetchState,
    onFetch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var link by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch TURN Credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a VK Call or Yandex Telemost link to auto-fetch TURN server credentials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("VK / Yandex Link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = fetchState != MainViewModel.FetchState.LOADING,
                    placeholder = { Text("https://vk.com/call/join/...") }
                )
                when (fetchState) {
                    MainViewModel.FetchState.LOADING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Fetching credentials...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    MainViewModel.FetchState.SUCCESS -> {
                        Text(
                            "Server added successfully!",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    MainViewModel.FetchState.ERROR -> {
                        Text(
                            "Failed to fetch credentials. Check the link and try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    MainViewModel.FetchState.IDLE -> {}
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onFetch(link) },
                enabled = link.isNotBlank() && fetchState != MainViewModel.FetchState.LOADING
            ) {
                Text("Fetch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
