package com.hightemp.turn_proxy_connector

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TurnProxyApp : Application() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "proxy_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TURN Proxy Connector service status"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
