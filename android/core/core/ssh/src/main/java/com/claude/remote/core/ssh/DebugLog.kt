package com.claude.remote.core.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val entry = "[$time] $tag: $message"
        _logs.value = (_logs.value + entry).takeLast(200)
        android.util.Log.d("CR_$tag", message)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAll(): String = _logs.value.joinToString("\n")
}
