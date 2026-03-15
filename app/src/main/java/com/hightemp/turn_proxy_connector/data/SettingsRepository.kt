package com.hightemp.turn_proxy_connector.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("turn_proxy_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_SERVERS = "turn_servers"
    }

    fun loadSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            gson.fromJson(json, AppSettings::class.java)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply()
    }

    fun loadServers(): List<TurnServer> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TurnServer>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServers(servers: List<TurnServer>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
    }
}
