package com.n15.gptlive.data.repo

import com.n15.gptlive.data.db.AppDatabase
import com.n15.gptlive.data.db.ChatSession
import com.n15.gptlive.data.db.MessageItem
import java.util.UUID

class ConversationRepository(private val db: AppDatabase) {

    suspend fun newSession(title: String): String {
        val id = UUID.randomUUID().toString()
        db.sessionDao().upsert(ChatSession(id, title, System.currentTimeMillis()))
        return id
    }

    suspend fun addUserText(sessionId: String, text: String) {
        db.messageDao().insert(
            MessageItem(UUID.randomUUID().toString(), sessionId, "user", text, System.currentTimeMillis())
        )
    }

    suspend fun addAssistantText(sessionId: String, text: String) {
        db.messageDao().insert(
            MessageItem(UUID.randomUUID().toString(), sessionId, "assistant", text, System.currentTimeMillis())
        )
    }

    suspend fun addToolText(sessionId: String, text: String) {
        db.messageDao().insert(
            MessageItem(UUID.randomUUID().toString(), sessionId, "tool", text, System.currentTimeMillis())
        )
    }
}
