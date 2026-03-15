package com.hightemp.turn_proxy_connector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hightemp.turn_proxy_connector.service.ProxyState
import com.hightemp.turn_proxy_connector.ui.viewmodels.MainViewModel

enum class AppTab(val title: String) {
    STATUS("Status"),
    SERVERS("Servers"),
    LOGS("Logs"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = AppTab.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TURN Proxy Connector") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (tab) {
                                    AppTab.STATUS -> Icons.Default.PowerSettingsNew
                                    AppTab.SERVERS -> Icons.Default.Dns
                                    AppTab.LOGS -> Icons.Default.Article
                                    AppTab.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tabs[selectedTab]) {
                AppTab.STATUS -> StatusScreen(viewModel)
                AppTab.SERVERS -> ServersScreen(viewModel)
                AppTab.LOGS -> LogsScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
