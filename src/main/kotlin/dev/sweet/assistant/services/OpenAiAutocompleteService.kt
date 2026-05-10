package dev.sweet.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweet.assistant.autocomplete.edit.EditorDiagnostic
import dev.sweet.assistant.autocomplete.edit.FileChunk
import dev.sweet.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweet.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweet.assistant.autocomplete.edit.NextEditAutocompletion
import dev.sweet.assistant.autocomplete.edit.UserAction
import dev.sweet.assistant.settings.SweetSettings
import dev.sweet.assistant.utils.defaultJson
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Service(Service.Level.PROJECT)
class OpenAiAutocompleteService(
    @Suppress("unused") private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(OpenAiAutocompleteService::class.java)
        private const val PREFIX_CONTEXT_LIMIT = 16_000
        private const val SUFFIX_CONTEXT_LIMIT = 12_000
        private const val RECENT_CHANGES_LIMIT = 6_000
        private const val CHUNK_LIMIT = 4_000
        private const val MAX_LOGGED_ERROR_BODY_LENGTH = 2_000
        private const val REQUEST_TITLE = "Sweet Autocomplete"

        fun getInstance(project: Project): OpenAiAutocompleteService = project.getService(OpenAiAutocompleteService::class.java)
    }

    private var lastLatencyMs: Long = -1L

    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        val startedAt = System.currentTimeMillis()
        if (request.ping) {
            return buildResponse(request, completion = "", confidence = 0f, startedAt = startedAt)
        }

        val settings = SweetSettings.getInstance()
        if (!settings.isOpenAiConfigured) {
            throw IllegalStateException("OpenAI-compatible API settings are incomplete")
        }

        return try {
            val completion = normalizeInsertion(request, sanitizeCompletion(fetchCompletion(request, settings)))
            lastLatencyMs = System.currentTimeMillis() - startedAt
            buildResponse(request, completion, if (completion.isNotEmpty()) 0.72f else 0f, startedAt)
        } catch (e: Exception) {
            logger.warn("OpenAI-compatible autocomplete request failed: ${e.message}")
            throw e
        }
    }

    fun getLastLatencyMs(): Long = lastLatencyMs

    fun updateLastUserActionTimestamp() = Unit

    override fun dispose() = Unit

    private suspend fun fetchCompletion(
        request: NextEditAutocompleteRequest,
        settings: SweetSettings,
    ): String {
        val upstreamRequest =
            ChatCompletionRequest(
                model = settings.openAiModel,
                temperature = settings.openAiTemperature,
                maxTokens = settings.openAiMaxTokens,
                messages = buildMessages(request),
            )
        val postData = defaultJson.encodeToString(ChatCompletionRequest.serializer(), upstreamRequest)
        val timeout = Duration.ofMillis(settings.openAiRequestTimeoutMs.toLong())
        val httpRequest =
            HttpRequest
                .newBuilder()
                .uri(URI.create(chatCompletionsUrl(settings.openAiBaseUrl)))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${settings.openAiApiKey}")
                .header("X-Title", REQUEST_TITLE)
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build()

        val response =
            buildHttpClient(settings)
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .await()

        if (response.statusCode() !in 200..399) {
            throw IOException("OpenAI-compatible API returned HTTP ${response.statusCode()}: ${response.body().take(MAX_LOGGED_ERROR_BODY_LENGTH)}")
        }

        val completion = defaultJson.decodeFromString(ChatCompletionResponse.serializer(), response.body())
        return completion.choices.firstOrNull()?.message?.content.orEmpty()
    }

    private fun buildHttpClient(settings: SweetSettings): HttpClient {
        val builder =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(settings.openAiRequestTimeoutMs.toLong()))

        parseProxyAddress(settings.openAiProxy)?.let { builder.proxy(ProxySelector.of(it)) }
        return builder.build()
    }

    private fun parseProxyAddress(value: String): InetSocketAddress? {
        val proxy = value.trim()
        if (proxy.isBlank()) return null

        val uri =
            try {
                URI(if (proxy.contains("://")) proxy else "http://$proxy")
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid proxy URL: $proxy", e)
            }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Proxy URL must use http or https scheme"
        }

        val host = uri.host ?: throw IllegalArgumentException("Invalid proxy URL: host is missing")
        val port =
            when {
                uri.port > 0 -> uri.port
                scheme == "https" -> 443
                else -> 80
            }
        return InetSocketAddress.createUnresolved(host, port)
    }

    private fun chatCompletionsUrl(baseUrl: String): String = "${baseUrl.trim().trimEnd('/')}/chat/completions"

    private fun buildMessages(request: NextEditAutocompleteRequest): List<ChatMessage> {
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val prefix = request.file_contents.substring(0, cursor)
        val suffix = request.file_contents.substring(cursor)

        return listOf(
            ChatMessage(
                role = "system",
                content =
                    listOf(
                        "You are a low-latency code autocomplete engine.",
                        "Return only JSON with this exact shape: {\"completion\":\"text to insert at the cursor\"}.",
                        "The completion must be inserted at <CURSOR>; do not repeat the existing prefix or suffix.",
                        "Keep the completion minimal and useful. Do not include markdown fences, explanations, or comments about the task.",
                        "If no useful completion exists, return {\"completion\":\"\"}.",
                    ).joinToString(" "),
            ),
            ChatMessage(
                role = "user",
                content = buildPrompt(request, prefix, suffix),
            ),
        )
    }

    private fun buildPrompt(
        request: NextEditAutocompleteRequest,
        prefix: String,
        suffix: String,
    ): String {
        val chunks =
            listOf(
                formatChunks("Relevant open file chunks", request.file_chunks),
                formatChunks("Retrieved context chunks", request.retrieval_chunks),
            ).filter { it.isNotBlank() }

        return listOf(
            "Repository: ${request.repo_name.ifBlank { "unknown" }}",
            "Branch: ${request.branch ?: "unknown"}",
            "File: ${request.file_path}",
            "",
            "Current file with cursor:",
            "```",
            "${truncateLeft(prefix, PREFIX_CONTEXT_LIMIT)}<CURSOR>${truncate(suffix, SUFFIX_CONTEXT_LIMIT)}",
            "```",
            "",
            request.recent_changes.takeIf { it.isNotBlank() }?.let { "Recent changes:\n${truncate(it, RECENT_CHANGES_LIMIT)}" },
            request.recent_changes_high_res.takeIf { it.isNotBlank() }?.let {
                "Recent high resolution changes:\n${truncate(it, RECENT_CHANGES_LIMIT)}"
            },
            formatDiagnostics(request.editor_diagnostics),
            formatActions(request.recent_user_actions),
            *chunks.toTypedArray(),
            "",
            "Return JSON only.",
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun formatChunks(
        title: String,
        chunks: List<FileChunk>,
    ): String {
        if (chunks.isEmpty()) return ""

        val rendered =
            chunks.take(8).joinToString("\n") { chunk ->
                listOf(
                    "--- ${chunk.file_path}:${chunk.start_line}-${chunk.end_line} ---",
                    truncate(chunk.content, CHUNK_LIMIT),
                ).joinToString("\n")
            }

        return "$title:\n$rendered"
    }

    private fun formatDiagnostics(diagnostics: List<EditorDiagnostic>): String {
        if (diagnostics.isEmpty()) return ""

        return "Editor diagnostics:\n" +
            diagnostics
                .take(12)
                .joinToString("\n") { diagnostic ->
                    "line ${diagnostic.line}, ${diagnostic.severity}: ${diagnostic.message}"
                }
    }

    private fun formatActions(actions: List<UserAction>): String {
        if (actions.isEmpty()) return ""

        return "Recent user actions:\n" +
            actions
                .takeLast(12)
                .joinToString("\n") { action ->
                    "${action.action_type} at ${action.file_path}:${action.line_number}:${action.offset}"
                }
    }

    private fun sanitizeCompletion(value: String): String {
        val parsed = parseCompletionContent(stripMarkdownFence(value.trim()))
        val completion =
            stripMarkdownFence(parsed)
                .replace("\r\n", "\n")
                .replace(Regex("</?CURSOR>"), "")
                .replace(Regex("^['\"]([\\s\\S]*)['\"]$"), "$1")

        return if (Regex("^(no completion|none|null|undefined)$", RegexOption.IGNORE_CASE).matches(completion.trim())) {
            ""
        } else {
            completion
        }
    }

    private fun parseCompletionContent(content: String): String =
        try {
            val parsed = defaultJson.parseToJsonElement(content).jsonObject
            parsed["completion"]?.jsonPrimitive?.contentOrNull
                ?: ((parsed["completions"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("completion")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: ""
        } catch (_: Exception) {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    defaultJson
                        .parseToJsonElement(content.substring(start, end + 1))
                        .jsonObject["completion"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: content
                } catch (_: Exception) {
                    content
                }
            } else {
                content
            }
        }

    private fun stripMarkdownFence(value: String): String {
        val fenceMatch = Regex("^```(?:json|javascript|typescript|ts|[a-zA-Z0-9_-]+)?\\s*\\r?\\n([\\s\\S]*?)\\r?\\n```$").matchEntire(value)
        return fenceMatch?.groupValues?.getOrNull(1) ?: value
    }

    private fun normalizeInsertion(
        request: NextEditAutocompleteRequest,
        completion: String,
    ): String {
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val prefix = request.file_contents.substring(0, cursor)
        val suffix = request.file_contents.substring(cursor)

        return trimSuffixOverlap(trimPrefixOverlap(completion, prefix), suffix)
    }

    private fun trimPrefixOverlap(
        completion: String,
        prefix: String,
    ): String {
        val maxOverlap = minOf(completion.length, prefix.length, 200)
        for (length in maxOverlap downTo 1) {
            if (prefix.endsWith(completion.substring(0, length))) {
                return completion.substring(length)
            }
        }
        return completion
    }

    private fun trimSuffixOverlap(
        completion: String,
        suffix: String,
    ): String {
        val maxOverlap = minOf(completion.length, suffix.length, 200)
        for (length in maxOverlap downTo 1) {
            if (suffix.startsWith(completion.substring(completion.length - length))) {
                return completion.substring(0, completion.length - length)
            }
        }
        return completion
    }

    private fun buildResponse(
        request: NextEditAutocompleteRequest,
        completion: String,
        confidence: Float,
        startedAt: Long,
    ): NextEditAutocompleteResponse {
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val autocompleteId = UUID.randomUUID().toString()
        val item =
            NextEditAutocompletion(
                start_index = cursor,
                end_index = cursor,
                completion = completion,
                confidence = confidence,
                autocomplete_id = autocompleteId,
            )

        return NextEditAutocompleteResponse(
            start_index = item.start_index,
            end_index = item.end_index,
            completion = item.completion,
            confidence = item.confidence,
            autocomplete_id = autocompleteId,
            elapsed_time_ms = System.currentTimeMillis() - startedAt,
            completions = if (completion.isNotEmpty()) listOf(item) else emptyList(),
        )
    }

    private fun truncate(
        value: String,
        maxLength: Int,
    ): String = if (value.length <= maxLength) value else "${value.take(maxLength)}\n...[truncated]"

    private fun truncateLeft(
        value: String,
        maxLength: Int,
    ): String = if (value.length <= maxLength) value else "[truncated]...\n${value.takeLast(maxLength)}"
}

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val temperature: Double,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val messages: List<ChatMessage>,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<ChatCompletionChoice> = emptyList(),
)

@Serializable
private data class ChatCompletionChoice(
    val message: ChatCompletionMessage? = null,
)

@Serializable
private data class ChatCompletionMessage(
    val content: String? = null,
)
