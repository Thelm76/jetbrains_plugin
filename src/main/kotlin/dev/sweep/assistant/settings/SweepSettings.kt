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
        private const val DEFAULT_AUTOCOMPLETE_LOCAL_PORT = 8081

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

    var autocompleteLocalPort: Int = DEFAULT_AUTOCOMPLETE_LOCAL_PORT
        set(value) {
            field = value.coerceIn(1, 65535)
            notifySettingsChanged()
        }

    val autocompleteLocalMode: Boolean
        get() = true

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
        autocompleteLocalPort = autocompleteLocalPort.coerceIn(1, 65535)
        if (autocompleteExclusionPatterns.isEmpty()) {
            autocompleteExclusionPatterns = mutableSetOf(".env")
        }
    }
}
