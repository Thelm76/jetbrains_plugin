package dev.sweep.assistant.components

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweep.assistant.settings.SweepSettings

data class SweepConfigState(
    @Deprecated("Moved to application-level SweepSettings.")
    var debounceThresholdMs: Long = -1L,
    @Deprecated("Moved to application-level SweepSettings.")
    var autocompleteDebounceMs: Long = -1L,
    @Deprecated("Autocomplete is local-only.")
    var isAutocompleteLocalMode: Boolean = true,
    @Deprecated("Moved to application-level SweepSettings.")
    var showAutocompleteBadge: Boolean = false,
    @Deprecated("Moved to application-level SweepSettings.")
    var autocompleteExclusionPatterns: Set<String> = emptySet(),
    @Deprecated("Moved to application-level SweepSettings.")
    var autocompleteExclusionPatternsV2: Set<String> = setOf(".env"),
    @Deprecated("Moved to application-level SweepSettings.")
    var hideAutocompleteExclusionBanner: Boolean = false,
    @Deprecated("Moved to application-level SweepSettings.")
    var disableConflictingPlugins: Boolean = true,
)

@State(
    name = "dev.sweep.assistant.components.SweepConfig",
    storages = [Storage("SweepConfig.xml")],
)
@Service(Service.Level.PROJECT)
class SweepConfig(
    private val project: Project,
) : PersistentStateComponent<SweepConfigState> {
    companion object {
        fun getInstance(project: Project): SweepConfig = project.getService(SweepConfig::class.java)
    }

    private var state = SweepConfigState()

    override fun getState(): SweepConfigState = state

    override fun loadState(state: SweepConfigState) {
        XmlSerializerUtil.copyBean(state, this.state)
        migrateAutocompleteSettings(state)
    }

    private fun migrateAutocompleteSettings(oldState: SweepConfigState) {
        val settings = SweepSettings.getInstance()

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

    fun getDebounceThresholdMs(): Long = SweepSettings.getInstance().autocompleteDebounceMs

    fun isAutocompleteLocalMode(): Boolean = true

    fun isPrivacyModeEnabled(): Boolean = false

    fun isShowAutocompleteBadge(): Boolean = SweepSettings.getInstance().showAutocompleteBadge

    fun getAutocompleteExclusionPatterns(): Set<String> = SweepSettings.getInstance().autocompleteExclusionPatterns

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        SweepSettings.getInstance().autocompleteExclusionPatterns = patterns.filter { it.isNotBlank() }.toMutableSet()
    }

    fun updateHideAutocompleteExclusionBanner(hidden: Boolean) {
        SweepSettings.getInstance().hideAutocompleteExclusionBanner = hidden
    }
}
