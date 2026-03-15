package com.hightemp.turn_proxy_connector.ui.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hightemp.turn_proxy_connector.data.AppSettings
import com.hightemp.turn_proxy_connector.data.SettingsRepository
import com.hightemp.turn_proxy_connector.data.TurnServer
import com.hightemp.turn_proxy_connector.service.ProxyService
import com.hightemp.turn_proxy_connector.service.ProxyState
import com.hightemp.turn_proxy_connector.util.LogBuffer
import com.hightemp.turn_proxy_connector.util.LogEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)

    private val _settings = MutableStateFlow(repo.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _servers = MutableStateFlow(repo.loadServers())
    val servers: StateFlow<List<TurnServer>> = _servers.asStateFlow()

    private val _proxyState = MutableStateFlow(ProxyState.STOPPED)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _activeConnections = MutableStateFlow(0L)
    val activeConnections: StateFlow<Long> = _activeConnections.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = LogBuffer.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var proxyService: ProxyService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ProxyService.LocalBinder).getService()
            proxyService = service
            isBound = true
            // Observe service state
            viewModelScope.launch {
                service.state.collect { state ->
                    _proxyState.value = state
                }
            }
            // Poll active connections
            viewModelScope.launch {
                while (isBound) {
                    _activeConnections.value = service.activeConnections
                    delay(1000)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isBound = false
            _proxyState.value = ProxyState.STOPPED
        }
    }

    init {
        LogBuffer.setMaxLines(_settings.value.maxLogLines)
        bindService()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, ProxyService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
        super.onCleared()
    }

    fun startProxy() {
        val context = getApplication<Application>()
        val intent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        }
        context.startForegroundService(intent)
        if (!isBound) {
            bindService()
        }
    }

    fun stopProxy() {
        proxyService?.stopProxy()
    }

    fun toggleProxy() {
        when (_proxyState.value) {
            ProxyState.STOPPED -> startProxy()
            ProxyState.RUNNING -> stopProxy()
            else -> {} // Ignore during transition
        }
    }

    // --- Settings ---

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        repo.saveSettings(newSettings)
        LogBuffer.setMaxLines(newSettings.maxLogLines)
    }

    // --- Servers ---

    fun addServer(server: TurnServer) {
        val updated = _servers.value + server
        _servers.value = updated
        repo.saveServers(updated)
    }

    fun updateServer(server: TurnServer) {
        val updated = _servers.value.map {
            if (it.id == server.id) server else it
        }
        _servers.value = updated
        repo.saveServers(updated)
    }

    fun deleteServer(serverId: String) {
        val updated = _servers.value.filter { it.id != serverId }
        _servers.value = updated
        repo.saveServers(updated)
    }

    fun clearLogs() {
        LogBuffer.clear()
    }
}
