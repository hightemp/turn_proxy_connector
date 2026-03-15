package com.hightemp.turn_proxy_connector.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hightemp.turn_proxy_connector.MainActivity
import com.hightemp.turn_proxy_connector.R
import com.hightemp.turn_proxy_connector.TurnProxyApp
import com.hightemp.turn_proxy_connector.data.AppSettings
import com.hightemp.turn_proxy_connector.data.SettingsRepository
import com.hightemp.turn_proxy_connector.proxy.HttpProxyServer
import com.hightemp.turn_proxy_connector.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProxyState {
    STOPPED, STARTING, RUNNING, STOPPING
}

class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.hightemp.turn_proxy_connector.START"
        const val ACTION_STOP = "com.hightemp.turn_proxy_connector.STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var proxyServer: HttpProxyServer? = null

    private val _state = MutableStateFlow(ProxyState.STOPPED)
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    val activeConnections: Long
        get() = proxyServer?.activeConnections ?: 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun startProxy() {
        if (_state.value == ProxyState.RUNNING || _state.value == ProxyState.STARTING) {
            return
        }

        _state.value = ProxyState.STARTING
        LogBuffer.i(TAG, "Starting proxy service...")

        val repo = SettingsRepository(applicationContext)
        val settings = repo.loadSettings()

        if (settings.serverHost.isBlank()) {
            LogBuffer.e(TAG, "Server host is not configured!")
            _state.value = ProxyState.STOPPED
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("Proxy starting..."))

        proxyServer = HttpProxyServer(settings, serviceScope).also {
            it.start()
        }

        _state.value = ProxyState.RUNNING
        updateNotification("Proxy running on :${settings.proxyPort}")
        LogBuffer.i(TAG, "Proxy service started")
    }

    fun stopProxy() {
        if (_state.value == ProxyState.STOPPED || _state.value == ProxyState.STOPPING) {
            return
        }

        _state.value = ProxyState.STOPPING
        LogBuffer.i(TAG, "Stopping proxy service...")

        proxyServer?.stop()
        proxyServer = null

        _state.value = ProxyState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBuffer.i(TAG, "Proxy service stopped")
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TurnProxyApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TURN Proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
