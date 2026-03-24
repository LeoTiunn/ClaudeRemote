package com.claude.remote.core.tmux

data class TmuxSession(
    val name: String,
    val windowName: String,
    val createdAt: Long = System.currentTimeMillis()
)