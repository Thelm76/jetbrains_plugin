package dev.sweep.assistant.startup

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.EditorActionsRouterService
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.services.IdeaVimIntegrationService
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.disableFullLineCompletion
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    override suspend fun execute(project: Project) {
        EditorActionsRouterService.getInstance()
        VimMotionGhostTextService.getInstance()
        RecentEditsTracker.getInstance(project)
        IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()

        if (SweepSettings.getInstance().disableConflictingPlugins) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    disableFullLineCompletion(project)
                }
            }
        }

        ApplicationManager.getApplication().invokeLater {
            ensureEditAutocompleteActionsAreBound()
        }
    }

    private fun ensureEditAutocompleteActionsAreBound() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val acceptActionId = AcceptEditCompletionAction.ACTION_ID
        val rejectActionId = RejectEditCompletionAction.ACTION_ID

        if (keymap.getShortcuts(acceptActionId).isEmpty()) {
            keymap.addShortcut(
                acceptActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), null),
            )
        }

        if (keymap.getShortcuts(rejectActionId).isEmpty()) {
            keymap.addShortcut(
                rejectActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), null),
            )
        }
    }
}
