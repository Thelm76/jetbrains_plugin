package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.settings.SweepSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.APP)
class LocalAutocompleteServerManager : Disposable {
    companion object {
        private val logger = Logger.getInstance(LocalAutocompleteServerManager::class.java)
        private const val DEFAULT_PORT = 8081
        private const val HEALTH_CHECK_TIMEOUT_MS = 3000L

        fun getInstance(): LocalAutocompleteServerManager =
            ApplicationManager.getApplication().getService(LocalAutocompleteServerManager::class.java)
    }

    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
            .build()

    private fun getPort(): Int =
        try {
            SweepSettings.getInstance().autocompleteLocalPort
        } catch (_: Exception) {
            DEFAULT_PORT
        }

    fun getServerUrl(): String = "http://localhost:${getPort()}"

    fun isServerHealthy(): Boolean =
        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(getServerUrl()))
                    .timeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MS))
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..499
        } catch (e: Exception) {
            false
        }

    fun reportSuccess() {
        // The local server is externally managed.
    }

    fun reportFailure() {
        logger.info("Local autocomplete request failed. The plugin will not restart the external server.")
    }

    override fun dispose() = Unit
}
