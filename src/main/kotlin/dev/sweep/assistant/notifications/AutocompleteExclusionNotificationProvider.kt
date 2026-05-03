package dev.sweep.assistant.notifications

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.matchesExclusionPattern
import java.util.function.Function
import javax.swing.JComponent

class AutocompleteExclusionNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val settings = SweepSettings.getInstance()
        if (settings.hideAutocompleteExclusionBanner) return null
        if (settings.autocompleteExclusionPatterns.none { matchesExclusionPattern(file.name, it) }) return null

        return Function { fileEditor ->
            if (fileEditor !is TextEditor) return@Function null
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                text = "Sweep autocomplete is disabled for this file by exclusion settings."
                createActionLabel("Do not show again") {
                    SweepConfig.getInstance(project).updateHideAutocompleteExclusionBanner(true)
                }
            }
        }
    }
}
