package dev.sweet.assistant.notifications

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.sweet.assistant.components.SweetConfig
import dev.sweet.assistant.settings.SweetSettings
import dev.sweet.assistant.utils.matchesExclusionPattern
import java.util.function.Function
import javax.swing.JComponent

class AutocompleteExclusionNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val settings = SweetSettings.getInstance()
        if (settings.hideAutocompleteExclusionBanner) return null
        if (settings.autocompleteExclusionPatterns.none { matchesExclusionPattern(file.name, it) }) return null

        return Function { fileEditor ->
            if (fileEditor !is TextEditor) return@Function null
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                text = "Sweet autocomplete is disabled for this file by exclusion settings."
                createActionLabel("Do not show again") {
                    SweetConfig.getInstance(project).updateHideAutocompleteExclusionBanner(true)
                }
            }
        }
    }
}
