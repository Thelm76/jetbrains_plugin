package dev.sweep.assistant.services

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.settings.SweepSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Service for communicating with OpenAI-compatible API endpoints (LM Studio, Ollama, etc.)
 * Handles both model listing and chat completions.
 *
 * Uses HttpURLConnection instead of java.net.http.HttpClient because the latter
 * has known timeout issues with some local HTTP servers on macOS.
 */
@Service(Service.Level.APP)
class OpenAIChatService {
    private val logger = Logger.getInstance(OpenAIChatService::class.java)

    companion object {
        fun getInstance(): OpenAIChatService =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(OpenAIChatService::class.java)

        /** Check if the configured baseUrl looks like a Sweep backend vs a generic OpenAI endpoint. */
        fun isSweepBackend(): Boolean {
            val url = SweepSettings.getInstance().baseUrl
            return url.contains("sweep.dev") || url.contains("sweep-")
        }
    }

    /**
     * Fetch available models from /v1/models endpoint.
     * Returns a map of displayName -> modelId.
     */
    fun fetchModels(): Map<String, String> {
        val baseUrl = SweepSettings.getInstance().baseUrl.trimEnd('/')
        if (baseUrl.isEmpty()) return emptyMap()

        val apiKey = SweepSettings.getInstance().githubToken

        return try {
            val url = URL("$baseUrl/v1/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            val status = conn.responseCode
            if (status != 200) {
                logger.warn("Failed to fetch models from $baseUrl/v1/models: HTTP $status")
                conn.disconnect()
                return emptyMap()
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return emptyMap()

            val models = mutableMapOf<String, String>()
            for (element in data) {
                val model = element.asJsonObject
                val id = model.get("id")?.asString ?: continue
                val displayName = id
                    .removePrefix("models/")
                    .removeSuffix(".gguf")
                models[displayName] = id
            }

            logger.info("Fetched ${models.size} models from $baseUrl/v1/models")
            models
        } catch (e: Exception) {
            logger.warn("Error fetching models from $baseUrl/v1/models: ${e.message}")
            emptyMap()
        }
    }

    data class ChatMessage(val role: String, val content: String)

    /**
     * Stream a chat completion from an OpenAI-compatible endpoint.
     * Calls the callback with each text chunk as it arrives.
     */
    fun streamChatCompletion(
        messages: List<ChatMessage>,
        model: String,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val baseUrl = SweepSettings.getInstance().baseUrl.trimEnd('/')
        val apiKey = SweepSettings.getInstance().githubToken

        // Build JSON request manually to avoid Gson dependency issues
        val messagesJson = messages.joinToString(",") { msg ->
            """{"role":"${msg.role}","content":${com.google.gson.Gson().toJson(msg.content)}}"""
        }
        val requestJson = """{"model":${com.google.gson.Gson().toJson(model)},"messages":[$messagesJson],"stream":true}"""

        try {
            val url = URL("$baseUrl/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 300_000 // 5 minutes for long responses
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestJson)
                writer.flush()
            }

            val status = conn.responseCode
            if (status != 200) {
                val errorBody = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                onError(RuntimeException("HTTP $status: $errorBody"))
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Check cancellation on each line
                    if (isCancelled()) {
                        logger.info("Chat stream cancelled by user")
                        conn.disconnect()
                        onDone()
                        return
                    }

                    val l = line ?: continue
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val event = JsonParser.parseString(data).asJsonObject
                        val choices = event.getAsJsonArray("choices") ?: continue
                        if (choices.size() == 0) continue
                        val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                        val content = delta.get("content")?.asString
                        if (content != null) {
                            onChunk(content)
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE events
                    }
                }
            }

            conn.disconnect()
            onDone()
        } catch (e: Exception) {
            onError(e)
        }
    }
}
