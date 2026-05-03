package dev.sweet.assistant.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.jetbrains.annotations.Nls

class AutocompleteStatusBarWidgetFactory : StatusBarWidgetFactory {
    companion object {
        const val ID = "SweetAutocompleteStatus"
    }

    override fun getId(): String = ID

    @Nls
    override fun getDisplayName(): String = "Sweet Tab"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AutocompleteStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
