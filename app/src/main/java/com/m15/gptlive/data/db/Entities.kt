package com.m15.gptlive.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ChatSession(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long
)

@Entity
data class MessageItem(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String,   // "user", "assistant" or "tool"
    val text: String,
    val createdAt: Long
)
