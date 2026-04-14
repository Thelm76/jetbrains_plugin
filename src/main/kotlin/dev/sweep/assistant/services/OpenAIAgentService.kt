package dev.sweep.assistant.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.settings.SweepSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible chat client with function-calling support.
 * Streams a single completion turn; the multi-turn agent loop and tool
 * execution live in [dev.sweep.assistant.controllers.Stream] and dispatch
 * through the existing SweepAgent pipeline.
 */
class OpenAIAgentService {
    private val logger = Logger.getInstance(OpenAIAgentService::class.java)
    private val gson = Gson()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** Force-close any in-flight HTTP connection (called from stop button thread). */
    fun cancelActiveRequest() {
        activeConnection?.let {
            try { it.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    companion object {
        private const val MAX_AGENT_TURNS = 20

        /** OpenAI function definitions for the core tools. */
        val TOOL_DEFINITIONS = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "read_file",
                    "description" to "Read the contents of a file. For large files, use offset and limit to read specific sections. If the file is very large, the output will be truncated — use offset/limit to read the parts you need.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf("type" to "string", "description" to "File path relative to the project root"),
                            "offset" to mapOf("type" to "integer", "description" to "Starting line number (1-based). Default: 1"),
                            "limit" to mapOf("type" to "integer", "description" to "Number of lines to read. Recommended: 200 for large files. Default: entire file"),
                        ),
                        "required" to listOf("path"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "search_files",
                    "description" to "Search for a regex pattern across files in the project. Returns matching lines with file paths and line numbers.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "regex" to mapOf("type" to "string", "description" to "Regular expression pattern to search for"),
                            "path" to mapOf("type" to "string", "description" to "Directory to search in (optional)"),
                            "glob" to mapOf("type" to "string", "description" to "File pattern filter, e.g. '*.py' (optional)"),
                        ),
                        "required" to listOf("regex"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "glob",
                    "description" to "Find files matching a glob pattern. Returns a list of file paths.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "pattern" to mapOf("type" to "string", "description" to "Glob pattern, e.g. '**/*.kt', 'src/*.py'"),
                            "path" to mapOf("type" to "string", "description" to "Directory to search in (optional)"),
                        ),
                        "required" to listOf("pattern"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "list_files",
                    "description" to "List files and directories in a directory tree.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf("type" to "string", "description" to "Directory path to list"),
                            "max_depth" to mapOf("type" to "integer", "description" to "Maximum recursion depth (optional)"),
                        ),
                        "required" to listOf("path"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "str_replace",
                    "description" to "Replace an exact string in a file with new content. The old_str must match exactly (including whitespace).",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf("type" to "string", "description" to "File path"),
                            "old_str" to mapOf("type" to "string", "description" to "Exact string to find and replace"),
                            "new_str" to mapOf("type" to "string", "description" to "Replacement string"),
                        ),
                        "required" to listOf("path", "old_str", "new_str"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "create_file",
                    "description" to "Create a new file with the given content.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf("type" to "string", "description" to "File path to create"),
                            "content" to mapOf("type" to "string", "description" to "File content"),
                        ),
                        "required" to listOf("path", "content"),
                    ),
                ),
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "bash",
                    "description" to "Execute a shell command and return the output. Use for running tests, git commands, installations, etc.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
                            "timeout" to mapOf("type" to "integer", "description" to "Timeout in seconds (default: 300, max: 1800)"),
                        ),
                        "required" to listOf("command"),
                    ),
                ),
            ),
        )
    }

    data class AgentMessage(
        val role: String,
        val content: String?,
        val toolCallId: String? = null,
        val name: String? = null,
        val toolCalls: List<ParsedToolCall>? = null, // For assistant messages with tool calls
    )

    data class ParsedToolCall(val id: String, val name: String, val arguments: Map<String, String>, val rawArguments: String = "")
    data class StreamResponse(val textContent: String?, val toolCalls: List<ParsedToolCall>)

    /**
     * Stream a single chat completion, returning text content and any tool calls.
     * Caller is responsible for the multi-turn loop (see Stream.kt).
     */
    fun streamWithToolCalls(
        messages: List<AgentMessage>,
        model: String,
        onTextChunk: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): StreamResponse {
        val baseUrl = SweepSettings.getInstance().baseUrl.trimEnd('/')
        val apiKey = SweepSettings.getInstance().githubToken

        // Build messages JSON with proper tool_calls format
        val messagesJson = messages.map { msg ->
            val obj = mutableMapOf<String, Any?>("role" to msg.role)
            // Always include content (null for assistant messages with tool calls)
            obj["content"] = msg.content ?: ""
            if (msg.toolCallId != null) obj["tool_call_id"] = msg.toolCallId
            // Don't include "name" on tool messages — not part of OpenAI spec
            // if (msg.name != null) obj["name"] = msg.name
            if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                obj["tool_calls"] = msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.name,
                            // arguments must be a JSON string, not an object
                            "arguments" to (tc.rawArguments.ifEmpty { gson.toJson(tc.arguments) }),
                        ),
                    )
                }
            }
            obj
        }

        val requestMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messagesJson,
            "stream" to true,
            "tools" to TOOL_DEFINITIONS,
        )

        val requestJson = gson.toJson(requestMap)

        // Log the full request JSON for debugging tool call issues
        logger.info("[Agent] Request JSON (first 2000 chars): ${requestJson.take(2000)}")
        // Log messages portion specifically
        val messagesOnlyJson = gson.toJson(messagesJson)
        logger.info("[Agent] Messages JSON (last 1000 chars): ...${messagesOnlyJson.takeLast(1000)}")

        val url = URL("$baseUrl/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        activeConnection = conn
        // Write request body
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(requestJson)
        writer.flush()
        writer.close()

        val status = conn.responseCode
        if (status != 200) {
            val errorBody = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            activeConnection = null
            throw RuntimeException("HTTP $status: $errorBody")
        }

        val textParts = StringBuilder()
        // Accumulate tool calls from deltas
        val toolCallAccumulator = mutableMapOf<Int, MutableMap<String, String>>() // index -> {id, name, arguments}

        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (isCancelled()) { conn.disconnect(); break }

                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val event = JsonParser.parseString(data).asJsonObject
                    val choices = event.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val choice = choices[0].asJsonObject
                    val delta = choice.getAsJsonObject("delta") ?: continue

                    // Text content
                    val content = delta.get("content")?.asString
                    if (content != null) {
                        textParts.append(content)
                        onTextChunk(content)
                    }

                    // Tool calls
                    val toolCalls = delta.getAsJsonArray("tool_calls")
                    if (toolCalls != null) {
                        for (tc in toolCalls) {
                            val tcObj = tc.asJsonObject
                            val index = tcObj.get("index")?.asInt ?: 0
                            val acc = toolCallAccumulator.getOrPut(index) { mutableMapOf("id" to "", "name" to "", "arguments" to "") }

                            tcObj.get("id")?.asString?.let { acc["id"] = it }
                            val function = tcObj.getAsJsonObject("function")
                            if (function != null) {
                                function.get("name")?.asString?.let { acc["name"] = it }
                                function.get("arguments")?.asString?.let { acc["arguments"] = acc["arguments"]!! + it }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        conn.disconnect()
        activeConnection = null

        // Parse accumulated tool calls
        val parsedToolCalls = toolCallAccumulator.values.mapNotNull { acc ->
            val name = acc["name"] ?: return@mapNotNull null
            val id = acc["id"] ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null

            val argsJson = acc["arguments"] ?: "{}"
            val args = try {
                val parsed = JsonParser.parseString(argsJson).asJsonObject
                parsed.entrySet().associate { entry ->
                    // Handle all JSON value types, not just strings
                    val value = when {
                        entry.value.isJsonPrimitive -> {
                            val prim = entry.value.asJsonPrimitive
                            when {
                                prim.isString -> prim.asString
                                prim.isNumber -> prim.asNumber.toString()
                                prim.isBoolean -> prim.asBoolean.toString()
                                else -> prim.toString()
                            }
                        }
                        else -> entry.value.toString() // Arrays, objects → raw JSON string
                    }
                    entry.key to value
                }
            } catch (e: Exception) {
                logger.warn("[Agent] Failed to parse tool arguments: $argsJson — ${e.message}")
                mapOf<String, String>()
            }

            logger.info("[Agent] Parsed tool call: $name($args) from JSON: ${argsJson.take(200)}")
            ParsedToolCall(id, name, args, argsJson)
        }

        return StreamResponse(textParts.toString().ifEmpty { null }, parsedToolCalls)
    }
}
