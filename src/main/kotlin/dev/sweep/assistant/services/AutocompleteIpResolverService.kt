package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.utils.defaultJson
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.raiseForStatus
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)
        private const val READ_TIMEOUT_MS = 10_000L

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)
    }

    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            val postData = encodeString(request, NextEditAutocompleteRequest.serializer())
            val httpRequest =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(postData))
                    .build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .await()
                    .raiseForStatus()

            var result: NextEditAutocompleteResponse? = null
            try {
                response.body().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.isBlank()) continue
                        try {
                            val jsonElement = defaultJson.parseToJsonElement(currentLine)
                            if (jsonElement is JsonObject && jsonElement.containsKey("status")) {
                                val status = jsonElement["status"]?.jsonPrimitive?.contentOrNull
                                if (status == "error") {
                                    val errorMsg = jsonElement["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                    logger.warn("Local autocomplete server error: $errorMsg")
                                    continue
                                }
                            }
                            result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), currentLine)
                        } catch (e: Exception) {
                            logger.warn("Error parsing local autocomplete response: ${e.message}")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                logger.info("Local autocomplete server stream closed: ${e.message}")
            }

            if (result != null) {
                LocalAutocompleteServerManager.getInstance().reportSuccess()
            } else {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }

            result
        } catch (e: Exception) {
            LocalAutocompleteServerManager.getInstance().reportFailure()
            logger.warn("Error fetching local autocomplete: ${e.message}")
            throw e
        }

    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    fun getLastLatencyMs(): Long = -1L

    fun updateLastUserActionTimestamp() = Unit

    override fun dispose() = Unit
}
