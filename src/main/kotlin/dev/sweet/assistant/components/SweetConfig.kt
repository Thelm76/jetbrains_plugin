package dev.sweet.assistant.components

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweet.assistant.settings.SweetSettings

data class SweetConfigState(
    @Deprecated("Moved to application-level SweetSettings.")
    var debounceThresholdMs: Long = -1L,
    @Deprecated("Moved to application-level SweetSettings.")
    var autocompleteDebounceMs: Long = -1L,
    @Deprecated("Autocomplete is local-only.")
    var isAutocompleteLocalMode: Boolean = true,
    @Deprecated("Moved to application-level SweetSettings.")
    var showAutocompleteBadge: Boolean = false,
    @Deprecated("Moved to application-level SweetSettings.")
    var autocompleteExclusionPatterns: Set<String> = emptySet(),
    @Deprecated("Moved to application-level SweetSettings.")
    var autocompleteExclusionPatternsV2: Set<String> = setOf(".env"),
    @Deprecated("Moved to application-level SweetSettings.")
    var hideAutocompleteExclusionBanner: Boolean = false,
    @Deprecated("Moved to application-level SweetSettings.")
    var disableConflictingPlugins: Boolean = true,
)

@State(
    name = "dev.sweet.assistant.components.SweetConfig",
    storages = [Storage("SweetConfig.xml")],
)
@Service(Service.Level.PROJECT)
class SweetConfig(
    private val project: Project,
) : PersistentStateComponent<SweetConfigState> {
    companion object {
        fun getInstance(project: Project): SweetConfig = project.getService(SweetConfig::class.java)
    }

    private var state = SweetConfigState()

    override fun getState(): SweetConfigState = state

    override fun loadState(state: SweetConfigState) {
        XmlSerializerUtil.copyBean(state, this.state)
        migrateAutocompleteSettings(state)
    }

    private fun migrateAutocompleteSettings(oldState: SweetConfigState) {
        val settings = SweetSettings.getInstance()

        if (settings.autocompleteDebounceMs == 10L) {
            val oldDebounce =
                when {
                    oldState.autocompleteDebounceMs > 0 -> oldState.autocompleteDebounceMs
                    oldState.debounceThresholdMs > 0 -> oldState.debounceThresholdMs
                    else -> null
                }
            oldDebounce?.let { settings.autocompleteDebounceMs = it }
        }

        val migratedPatterns =
            (oldState.autocompleteExclusionPatterns + oldState.autocompleteExclusionPatternsV2)
                .filter { it.isNotBlank() }
                .toMutableSet()
        if (migratedPatterns.isNotEmpty() && settings.autocompleteExclusionPatterns == mutableSetOf(".env")) {
            settings.autocompleteExclusionPatterns = migratedPatterns
        }

        settings.showAutocompleteBadge = settings.showAutocompleteBadge || oldState.showAutocompleteBadge
        settings.hideAutocompleteExclusionBanner = settings.hideAutocompleteExclusionBanner || oldState.hideAutocompleteExclusionBanner
        settings.disableConflictingPlugins = settings.disableConflictingPlugins && oldState.disableConflictingPlugins
    }

    fun getDebounceThresholdMs(): Long = SweetSettings.getInstance().autocompleteDebounceMs

    fun isAutocompleteLocalMode(): Boolean = true

    fun isPrivacyModeEnabled(): Boolean = false

    fun isShowAutocompleteBadge(): Boolean = SweetSettings.getInstance().showAutocompleteBadge

    fun getAutocompleteExclusionPatterns(): Set<String> = SweetSettings.getInstance().autocompleteExclusionPatterns

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        SweetSettings.getInstance().autocompleteExclusionPatterns = patterns.filter { it.isNotBlank() }.toMutableSet()
    }

    fun updateHideAutocompleteExclusionBanner(hidden: Boolean) {
        SweetSettings.getInstance().hideAutocompleteExclusionBanner = hidden
    }
}
