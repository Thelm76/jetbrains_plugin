package dev.sweet.assistant.autocomplete.edit

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.sweet.assistant.services.FeatureFlagService
import java.beans.PropertyChangeListener

/**
 * Handles IntelliJ lookup lifecycle events that affect autocomplete.
 */
class LookupUICustomizer(
    private val project: Project,
) : Disposable {
    private var propertyChangeListener: PropertyChangeListener? = null

    /**
     * Initializes the lookup listener to monitor when lookups become active
     * and coordinate autocomplete state accordingly.
     */
    fun initialize() {
        val lookupManager = LookupManager.getInstance(project)
        propertyChangeListener =
            PropertyChangeListener { event ->
                if (event.propertyName == LookupManager.PROP_ACTIVE_LOOKUP) {
                    if (event.newValue is LookupImpl) {
                        if (FeatureFlagService.getInstance(project).isFeatureEnabled("cancel_autocomplete_when_dropdown_appears")) {
                            // Cancel current autocomplete and refetch when lookup appears
                            val tracker = RecentEditsTracker.getInstance(project)
                            tracker.clearAutocomplete(AutocompleteDisposeReason.LOOKUP_SHOWN)
                            tracker.scheduleAutocompleteWithPrefetch()
                        }
                    }
                }
            }
        lookupManager.addPropertyChangeListener(propertyChangeListener!!)
    }

    override fun dispose() {
        // Property change listener will be cleaned up automatically when project is disposed
        propertyChangeListener = null
    }
}
