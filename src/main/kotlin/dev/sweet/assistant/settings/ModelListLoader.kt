package dev.sweet.assistant.settings

import dev.sweet.assistant.utils.defaultJson
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

object ModelListLoader {
    private const val DEFAULT_MODEL_LABEL = "Default"
    private const val REQUEST_TIMEOUT_MS = 30_000L

    fun loadModels(
        baseUrl: String,
        apiKey: String,
        proxyText: String,
    ): List<ModelInfo> {
        val uri = buildApiUri(baseUrl, "models")
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .GET()

        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $trimmedApiKey")
        }

        val response =
            buildHttpClient(proxyText)
                .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..399) {
            throw IOException("Failed to load models: HTTP ${response.statusCode()}.")
        }

        return parseModelList(response.body())
    }

    private fun buildApiUri(
        baseUrl: String,
        pathSuffix: String,
    ): URI {
        val trimmed = baseUrl.trim()
        require(trimmed.isNotEmpty()) { "Invalid API base URL." }

        val base =
            try {
                URI(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid API base URL.", e)
            }

        require(base.scheme == "http" || base.scheme == "https") { "Invalid API base URL." }
        require(!base.host.isNullOrBlank()) { "Invalid API base URL." }

        val normalizedPath =
            buildString {
                append(base.path.orEmpty().ifBlank { "/" })
                if (!endsWith('/')) append('/')
                append(pathSuffix.removePrefix("/"))
            }

        return URI(base.scheme, base.userInfo, base.host, base.port, normalizedPath, null, null)
    }

    private fun buildHttpClient(proxyText: String): HttpClient {
        val builder =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))

        parseProxyAddress(proxyText)?.let { builder.proxy(ProxySelector.of(it)) }
        return builder.build()
    }

    private fun parseProxyAddress(value: String): InetSocketAddress? {
        val proxy = value.trim()
        if (proxy.isBlank()) return null

        val uri =
            try {
                URI(if (proxy.contains("://")) proxy else "http://$proxy")
            } catch (e: Exception) {
                return null
            }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host ?: return null
        val port =
            when {
                uri.port > 0 -> uri.port
                scheme == "https" -> 443
                else -> 80
            }
        return InetSocketAddress.createUnresolved(host, port)
    }

    private fun parseModelList(payload: String): List<ModelInfo> {
        val root =
            try {
                defaultJson.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                throw IOException("Invalid response format.", e)
            }

        val data = root["data"] as? JsonArray ?: return emptyList()
        val seenIds = mutableSetOf<String>()
        val models = mutableListOf<ModelInfo>()

        data.forEach { value ->
            val obj = value as? JsonObject ?: return@forEach
            val id = obj.stringValue("id").trim()
            if (id.isEmpty() || id == DEFAULT_MODEL_LABEL || !seenIds.add(id)) {
                return@forEach
            }

            val pricing = obj["pricing"] as? JsonObject
            models +=
                ModelInfo(
                    id = id,
                    name = obj.stringValue("name"),
                    description = obj.stringValue("description"),
                    promptPrice = pricing.stringValue("prompt"),
                    completionPrice = pricing.stringValue("completion"),
                    inputCacheReadPrice = pricing.stringValue("input_cache_read"),
                    knowledgeCutoff = obj.stringValue("knowledge_cutoff"),
                )
        }

        return models
    }

    private fun JsonObject?.stringValue(key: String): String =
        runCatching {
            this
                ?.get(key)
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        }.getOrDefault("")
}
