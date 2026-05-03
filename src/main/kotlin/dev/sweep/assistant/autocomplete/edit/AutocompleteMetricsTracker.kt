package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class AutocompleteMetricsTracker(
    @Suppress("unused") private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): AutocompleteMetricsTracker = project.getService(AutocompleteMetricsTracker::class.java)
    }

    fun trackSuggestionShown(suggestion: AutocompleteSuggestion) = Unit

    fun trackSuggestionAccepted(suggestion: AutocompleteSuggestion) = Unit

    fun trackSuggestionRejected(suggestion: AutocompleteSuggestion) = Unit

    fun trackSuggestionDisposed(suggestion: AutocompleteSuggestion) = Unit

    fun trackFileContentsAfterDelay(
        document: Document,
        rangeMarker: RangeMarker?,
        suggestionType: String,
        additionsAndDeletions: Pair<Int, Int>,
        autocompleteId: String,
    ) = Unit
}
