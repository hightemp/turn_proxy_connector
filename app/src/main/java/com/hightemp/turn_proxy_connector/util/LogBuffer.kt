package com.hightemp.turn_proxy_connector.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String = "",
    val tag: String = ""
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestamp))
        }

    val formatted: String
        get() = "$formattedTime [${level.name}] ${if (tag.isNotEmpty()) "[$tag] " else ""}$message"
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

object LogBuffer {
    private var maxLines = 500
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun setMaxLines(max: Int) {
        maxLines = max
        trimLogs()
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            tag = tag
        )
        synchronized(this) {
            val current = _logs.value.toMutableList()
            current.add(entry)
            if (current.size > maxLines) {
                _logs.value = current.takeLast(maxLines)
            } else {
                _logs.value = current
            }
        }
        // Also log to logcat
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, message)
            LogLevel.INFO -> android.util.Log.i(tag, message)
            LogLevel.WARN -> android.util.Log.w(tag, message)
            LogLevel.ERROR -> android.util.Log.e(tag, message)
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun clear() {
        _logs.value = emptyList()
    }

    private fun trimLogs() {
        synchronized(this) {
            val current = _logs.value
            if (current.size > maxLines) {
                _logs.value = current.takeLast(maxLines)
            }
        }
    }
}
