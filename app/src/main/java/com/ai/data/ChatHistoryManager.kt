package com.ai.data

import android.content.Context
import com.ai.ui.ChatMessage
import com.ai.ui.ChatParameters
import com.ai.ui.ChatSession
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Singleton class to manage AI chat history storage.
 * Stores chat sessions as JSON files in the app's internal storage under a "chat-history" directory.
 */
object ChatHistoryManager {
    private const val HISTORY_DIR = "chat-history"
    private var historyDir: File? = null
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Initialize the manager with the app context.
     * Must be called before using any other methods.
     */
    fun init(context: Context) {
        historyDir = File(context.filesDir, HISTORY_DIR).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Save a chat session to storage.
     * If the session already exists (same ID), it will be updated.
     */
    fun saveSession(session: ChatSession): Boolean {
        val dir = historyDir ?: return false
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file = File(dir, "${session.id}.json")
        return try {
            val json = gson.toJson(session)
            file.writeText(json)
            android.util.Log.d("ChatHistoryManager", "Saved chat session: ${session.id}")
            true
        } catch (e: Exception) {
            android.util.Log.e("ChatHistoryManager", "Failed to save chat session: ${e.message}", e)
            false
        }
    }

    /**
     * Load a chat session by ID.
     */
    fun loadSession(sessionId: String): ChatSession? {
        val dir = historyDir ?: return null
        val file = File(dir, "$sessionId.json")
        if (!file.exists()) return null

        return try {
            val json = file.readText()
            gson.fromJson(json, ChatSession::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ChatHistoryManager", "Failed to load chat session: ${e.message}", e)
            null
        }
    }

    /**
     * Get all saved chat sessions, sorted by updatedAt (newest first).
     */
    fun getAllSessions(): List<ChatSession> {
        val dir = historyDir ?: return emptyList()
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    gson.fromJson(json, ChatSession::class.java)
                } catch (e: Exception) {
                    android.util.Log.e("ChatHistoryManager", "Failed to parse chat session: ${e.message}")
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /**
     * Delete a chat session by ID.
     */
    fun deleteSession(sessionId: String): Boolean {
        val dir = historyDir ?: return false
        val file = File(dir, "$sessionId.json")
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear all chat history.
     */
    fun clearHistory() {
        val dir = historyDir ?: return
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get the count of saved sessions.
     */
    fun getSessionCount(): Int {
        val dir = historyDir ?: return 0
        if (!dir.exists()) return 0
        return dir.listFiles { file -> file.extension == "json" }?.size ?: 0
    }
}
