package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_AUTOMATIC_AUTOCOMPLETE_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = 10L
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true
        private const val DEFAULT_OPENAI_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        private const val DEFAULT_OPENAI_TITLE = "Sweep Autocomplete Adapter"
        private const val DEFAULT_OPENAI_MAX_TOKENS = 512
        private const val DEFAULT_OPENAI_TEMPERATURE = 0.0
        private const val DEFAULT_OPENAI_REQUEST_TIMEOUT_MS = 30_000

        fun getInstance(): SweepSettings = ApplicationManager.getApplication().getService(SweepSettings::class.java)
    }

    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep autocomplete settings changed", SettingsChangedNotifier::class.java)
        }
    }

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var automaticAutocompleteOn: Boolean = DEFAULT_AUTOMATIC_AUTOCOMPLETE_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean = DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            field = value.coerceIn(10L, 1000L)
            notifySettingsChanged()
        }

    var disableConflictingPlugins: Boolean = DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var showAutocompleteBadge: Boolean = false
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var autocompleteExclusionPatterns: MutableSet<String> = mutableSetOf(".env")
        set(value) {
            field = value
            notifySettingsChanged()
        }

    var hideAutocompleteExclusionBanner: Boolean = false

    var openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL
        set(value) {
            field = value.trim().ifBlank { DEFAULT_OPENAI_BASE_URL }
            notifySettingsChanged()
        }

    var openAiProxy: String = ""
        set(value) {
            field = value.trim()
            notifySettingsChanged()
        }

    var openAiApiKey: String = ""
        set(value) {
            field = value.trim()
            notifySettingsChanged()
        }

    var openAiModel: String = DEFAULT_OPENAI_MODEL
        set(value) {
            field = value.trim().ifBlank { DEFAULT_OPENAI_MODEL }
            notifySettingsChanged()
        }

    var openAiTitle: String = DEFAULT_OPENAI_TITLE
        set(value) {
            field = value.trim().ifBlank { DEFAULT_OPENAI_TITLE }
            notifySettingsChanged()
        }

    var openAiMaxTokens: Int = DEFAULT_OPENAI_MAX_TOKENS
        set(value) {
            field = value.coerceIn(1, 128_000)
            notifySettingsChanged()
        }

    var openAiTemperature: Double = DEFAULT_OPENAI_TEMPERATURE
        set(value) {
            field = value.coerceIn(0.0, 2.0)
            notifySettingsChanged()
        }

    var openAiRequestTimeoutMs: Int = DEFAULT_OPENAI_REQUEST_TIMEOUT_MS
        set(value) {
            field = value.coerceIn(1_000, 600_000)
            notifySettingsChanged()
        }

    val isOpenAiConfigured: Boolean
        get() = openAiBaseUrl.isNotBlank() && openAiApiKey.isNotBlank() && openAiModel.isNotBlank()

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(SettingsChangedNotifier.TOPIC)
                .settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: SweepSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        XmlSerializerUtil.copyBean(state, this)
        autocompleteDebounceMs = autocompleteDebounceMs.coerceIn(10L, 1000L)
        openAiBaseUrl = openAiBaseUrl
        openAiProxy = openAiProxy
        openAiApiKey = openAiApiKey
        openAiModel = openAiModel
        openAiTitle = openAiTitle
        openAiMaxTokens = openAiMaxTokens
        openAiTemperature = openAiTemperature
        openAiRequestTimeoutMs = openAiRequestTimeoutMs
        if (autocompleteExclusionPatterns.isEmpty()) {
            autocompleteExclusionPatterns = mutableSetOf(".env")
        }
    }
}
