package com.example.mine_detector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val detailedLogs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun log(message: String, isDetailed: Boolean = false) {
        val timestamp = dateFormat.format(Date())
        val fullMessage = "[$timestamp] $message"
        
        if (!isDetailed) {
            _logs.value = (_logs.value + fullMessage).takeLast(100)
        }
        detailedLogs.add(fullMessage)
    }

    fun getDetailedLogs(): String {
        return detailedLogs.joinToString("\n")
    }
}
