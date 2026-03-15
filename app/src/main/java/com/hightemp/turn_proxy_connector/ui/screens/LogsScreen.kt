package com.hightemp.turn_proxy_connector.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hightemp.turn_proxy_connector.ui.viewmodels.MainViewModel
import com.hightemp.turn_proxy_connector.util.LogEntry
import com.hightemp.turn_proxy_connector.util.LogLevel
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Logs (${logs.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = {
                    autoScroll = true
                    coroutineScope.launch {
                        if (logs.isNotEmpty()) {
                            listState.animateScrollToItem(logs.size - 1)
                        }
                    }
                }) {
                    Icon(Icons.Default.ArrowDownward, "Scroll to bottom")
                }
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Default.ClearAll, "Clear logs")
                }
            }
        }

        // Log list
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No logs yet.\nStart the proxy to see activity.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(logs, key = { "${it.timestamp}-${it.message.hashCode()}" }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.DEBUG -> Color.Gray
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.WARN -> Color(0xFFFFA000)
        LogLevel.ERROR -> Color(0xFFF44336)
    }

    Text(
        text = entry.formatted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .horizontalScroll(rememberScrollState()),
        maxLines = 3
    )
}
