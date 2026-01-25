package com.ai.data

import android.util.Log
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple HTTP server for the Dummy AI Provider.
 * Provides OpenAI-compatible API responses for testing.
 *
 * Endpoints:
 * - POST /v1/chat/completions - Chat completion (returns "Hi, Greetings from AI")
 * - GET /v1/models - List models (returns abc, klm, xyz)
 *
 * API key must be "dummy" or returns 401 error.
 */
object DummyApiServer {
    private const val TAG = "DummyApiServer"
    const val PORT = 54321
    const val BASE_URL = "http://localhost:$PORT/"
    private const val VALID_API_KEY = "dummy"

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val gson = Gson()

    /**
     * Start the server if not already running.
     */
    fun start() {
        if (isRunning.get()) {
            Log.d(TAG, "Server already running on port $PORT")
            return
        }

        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                isRunning.set(true)
                Log.d(TAG, "Dummy API Server started on port $PORT")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error accepting connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server: ${e.message}")
                isRunning.set(false)
            }
        }
    }

    /**
     * Stop the server.
     */
    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)
        try {
            serverSocket?.close()
            serverSocket = null
            Log.d(TAG, "Dummy API Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    /**
     * Check if server is running.
     */
    fun isRunning(): Boolean = isRunning.get()

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)

                // Read request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                var contentLength = 0
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val colonIndex = line!!.indexOf(':')
                    if (colonIndex > 0) {
                        val key = line!!.substring(0, colonIndex).trim().lowercase()
                        val value = line!!.substring(colonIndex + 1).trim()
                        headers[key] = value
                        if (key == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                // Read body if present
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    reader.read(buffer, 0, contentLength)
                    String(buffer)
                } else ""

                // Check API key
                val authHeader = headers["authorization"] ?: ""
                val apiKey = authHeader.removePrefix("Bearer ").trim()

                // Route request
                val response = when {
                    path.startsWith("/v1/models") && method == "GET" -> handleModels(apiKey)
                    path.startsWith("/v1/chat/completions") && method == "POST" -> handleChatCompletion(apiKey, body)
                    else -> createErrorResponse(404, "Not Found", "Unknown endpoint: $path")
                }

                // Send response
                writer.print("HTTP/1.1 ${response.statusCode} ${response.statusText}\r\n")
                writer.print("Content-Type: application/json\r\n")
                writer.print("Content-Length: ${response.body.length}\r\n")
                writer.print("Connection: close\r\n")
                writer.print("\r\n")
                writer.print(response.body)
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        }
    }

    private fun handleModels(apiKey: String): HttpResponse {
        if (apiKey != VALID_API_KEY) {
            return createErrorResponse(401, "Unauthorized", "Invalid API key. Use 'dummy' as the API key.")
        }

        val modelsResponse = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf("id" to "abc", "object" to "model", "owned_by" to "dummy"),
                mapOf("id" to "klm", "object" to "model", "owned_by" to "dummy"),
                mapOf("id" to "xyz", "object" to "model", "owned_by" to "dummy")
            )
        )

        return HttpResponse(200, "OK", gson.toJson(modelsResponse))
    }

    private fun handleChatCompletion(apiKey: String, body: String): HttpResponse {
        if (apiKey != VALID_API_KEY) {
            return createErrorResponse(401, "Unauthorized", "Invalid API key. Use 'dummy' as the API key.")
        }

        // Parse request to get model (optional)
        val model = try {
            val request = gson.fromJson(body, Map::class.java)
            request["model"] as? String ?: "dummy-model"
        } catch (e: Exception) {
            "dummy-model"
        }

        val completionResponse = mapOf(
            "id" to "chatcmpl-dummy-${System.currentTimeMillis()}",
            "object" to "chat.completion",
            "created" to (System.currentTimeMillis() / 1000),
            "model" to model,
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "message" to mapOf(
                        "role" to "assistant",
                        "content" to "Hi, Greetings from AI"
                    ),
                    "finish_reason" to "stop"
                )
            ),
            "usage" to mapOf(
                "prompt_tokens" to 10,
                "completion_tokens" to 5,
                "total_tokens" to 15
            )
        )

        return HttpResponse(200, "OK", gson.toJson(completionResponse))
    }

    private fun createErrorResponse(statusCode: Int, statusText: String, message: String): HttpResponse {
        val errorBody = mapOf(
            "error" to mapOf(
                "message" to message,
                "type" to "invalid_request_error",
                "code" to statusCode
            )
        )
        return HttpResponse(statusCode, statusText, gson.toJson(errorBody))
    }

    private data class HttpResponse(
        val statusCode: Int,
        val statusText: String,
        val body: String
    )
}
