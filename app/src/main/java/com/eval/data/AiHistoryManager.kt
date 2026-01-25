package com.eval.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enum for AI report prompt types.
 */
enum class AiReportType(val prefix: String) {
    GENERAL("general"),
    GAME_ANALYSIS("game_analysis"),
    SERVER_PLAYER("server_player"),
    OTHER_PLAYER("other_player")
}

/**
 * Data class for displaying AI history files in a list.
 */
data class AiHistoryFileInfo(
    val filename: String,
    val reportType: AiReportType,
    val timestamp: Long,
    val file: File
)

/**
 * Singleton class to manage AI report history storage.
 * Stores HTML files in the app's internal storage under an "ai-history" directory.
 *
 * File naming convention: <prompt>_<date>_<time>.html
 * Example: server_player_20260124_0912.html
 */
object AiHistoryManager {
    private const val HISTORY_DIR = "ai-history"
    private var historyDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("HHmm", Locale.US)

    /**
     * Initialize the manager with the app context.
     * Must be called before using any other methods.
     */
    fun init(context: Context) {
        historyDir = File(context.filesDir, HISTORY_DIR)
        if (!historyDir!!.exists()) {
            historyDir!!.mkdirs()
        }
    }

    /**
     * Save an AI report HTML file to history.
     *
     * @param html The HTML content to save
     * @param reportType The type of report (GENERAL, GAME_ANALYSIS, SERVER_PLAYER, OTHER_PLAYER)
     * @return The saved file, or null if saving failed
     */
    fun saveReport(html: String, reportType: AiReportType): File? {
        val dir = historyDir ?: return null
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val now = Date()
        val dateStr = dateFormat.format(now)
        val timeStr = timeFormat.format(now)
        val filename = "${reportType.prefix}_${dateStr}_${timeStr}.html"
        val file = File(dir, filename)

        return try {
            file.writeText(html)
            android.util.Log.d("AiHistoryManager", "Saved AI report: $filename")
            file
        } catch (e: Exception) {
            android.util.Log.e("AiHistoryManager", "Failed to save AI report: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get all saved AI history files, sorted by timestamp (newest first).
     */
    fun getHistoryFiles(): List<AiHistoryFileInfo> {
        val dir = historyDir ?: return emptyList()
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file -> file.extension == "html" }
            ?.mapNotNull { file -> parseFileInfo(file) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Parse file info from a history file.
     */
    private fun parseFileInfo(file: File): AiHistoryFileInfo? {
        val name = file.nameWithoutExtension
        // Expected format: <type>_<date>_<time>
        // e.g., server_player_20260124_0912

        val reportType = when {
            name.startsWith("general_") -> AiReportType.GENERAL
            name.startsWith("game_analysis_") -> AiReportType.GAME_ANALYSIS
            name.startsWith("server_player_") -> AiReportType.SERVER_PLAYER
            name.startsWith("other_player_") -> AiReportType.OTHER_PLAYER
            else -> return null
        }

        // Extract date and time parts
        val parts = name.split("_")
        if (parts.size < 3) return null

        // Parse timestamp from date_time parts
        val timestamp = try {
            val dateTimePart = "${parts[parts.size - 2]}_${parts[parts.size - 1]}"
            val combinedFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
            combinedFormat.parse(dateTimePart)?.time ?: file.lastModified()
        } catch (e: Exception) {
            file.lastModified()
        }

        return AiHistoryFileInfo(
            filename = file.name,
            reportType = reportType,
            timestamp = timestamp,
            file = file
        )
    }

    /**
     * Delete a history file.
     */
    fun deleteFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear all history files.
     */
    fun clearHistory() {
        val dir = historyDir ?: return
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get the history directory.
     */
    fun getHistoryDir(): File? = historyDir
}
