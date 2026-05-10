package dev.sweet.assistant.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.util.Consumer
import com.intellij.vcsUtil.showAbove
import dev.sweet.assistant.services.AutocompleteRequestStatusService
import dev.sweet.assistant.settings.SweetSettings
import java.awt.event.MouseEvent
import javax.swing.Icon

class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweetAutocompleteStatus"
    }

    private val clickHandler = Consumer<MouseEvent> { event -> showPopupMenu(event) }
    private val requestStatusService = AutocompleteRequestStatusService.getInstance(project)
    private val requestStateListener = { updateWidget() }

    init {
        requestStatusService.addRequestStateListener(requestStateListener)
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                SweetSettings.SettingsChangedNotifier.TOPIC,
                SweetSettings.SettingsChangedNotifier { updateWidget() },
            )
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) = Unit

    override fun dispose() {
        requestStatusService.removeRequestStateListener(requestStateListener)
    }

    override fun getIcon(): Icon? =
        if (requestStatusService.isRequestInProgress) {
            AnimatedIcon.Default.INSTANCE
        } else {
            IconLoader.getIcon("/icons/sweet16x16.svg", javaClass)
        }

    override fun getClickConsumer(): Consumer<MouseEvent>? = clickHandler

    override fun getTooltipText(): String =
        when {
            requestStatusService.isRequestInProgress -> "Sweet Autocomplete: Fetching suggestion..."
            SweetSettings.getInstance().isOpenAiConfigured -> "Sweet Autocomplete: ${autocompleteModeText()}, OpenAI-compatible API configured"
            else -> "Sweet Autocomplete: OpenAI-compatible API settings incomplete"
        }

    private fun showPopupMenu(event: MouseEvent) {
        val menuItems = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val settings = SweetSettings.getInstance()

        menuItems.add(
            if (settings.automaticAutocompleteOn) {
                "Switch to Hotkey Only"
            } else {
                "Switch to Automatic"
            }
        )
        actions.add {
            settings.automaticAutocompleteOn = !settings.automaticAutocompleteOn
            updateWidget()
        }

        val popupStep =
            object : BaseListPopupStep<String>("Sweet Autocomplete", menuItems) {
                override fun onChosen(
                    selectedValue: String?,
                    finalChoice: Boolean,
                ): PopupStep<*>? {
                    if (finalChoice) {
                        val index = menuItems.indexOf(selectedValue)
                        if (index >= 0 && index < actions.size) actions[index].invoke()
                    }
                    return PopupStep.FINAL_CHOICE
                }
            }

        JBPopupFactory.getInstance().createListPopup(popupStep).showAbove(event.component)
    }

    private fun autocompleteModeText(): String =
        if (SweetSettings.getInstance().automaticAutocompleteOn) {
            "Automatic"
        } else {
            "Hotkey only"
        }

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }
}
