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
import dev.sweet.assistant.services.AutocompleteSnoozeService
import dev.sweet.assistant.settings.SweetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import javax.swing.Icon

class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweetAutocompleteStatus"
        private const val CHECK_INTERVAL_MS = 15_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isAlive = true
    private var clickHandler: Consumer<MouseEvent>? = null
    private val snoozeService = AutocompleteSnoozeService.getInstance(project)
    private val requestStatusService = AutocompleteRequestStatusService.getInstance(project)
    private val snoozeStateListener = { updateWidget() }
    private val requestStateListener = { updateWidget() }

    init {
        snoozeService.addSnoozeStateListener(snoozeStateListener)
        requestStatusService.addRequestStateListener(requestStateListener)
        startHealthCheck()
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) = Unit

    override fun dispose() {
        snoozeService.removeSnoozeStateListener(snoozeStateListener)
        requestStatusService.removeRequestStateListener(requestStateListener)
        scope.cancel()
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
            snoozeService.isAutocompleteSnooze() -> "Sweet Autocomplete: Snoozed (${snoozeService.formatRemainingTime()} remaining)"
            isAlive -> "Sweet Autocomplete: OpenAI-compatible API configured"
            else -> "Sweet Autocomplete: OpenAI-compatible API settings incomplete"
        }

    private fun startHealthCheck() {
        clickHandler = Consumer { event -> showPopupMenu(event) }
        scope.launch {
            while (isActive) {
                isAlive = performHealthCheck()
                updateWidget()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun showPopupMenu(event: MouseEvent) {
        scope.launch {
            isAlive = performHealthCheck()
            updateWidget()
        }

        val menuItems = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (snoozeService.isAutocompleteSnooze()) {
            menuItems.add("Unsnooze (${snoozeService.formatRemainingTime()} remaining)")
            actions.add { snoozeService.unsnooze() }
        } else {
            listOf(
                "Snooze for 5 minutes" to AutocompleteSnoozeService.SNOOZE_5_MINUTES,
                "Snooze for 15 minutes" to AutocompleteSnoozeService.SNOOZE_15_MINUTES,
                "Snooze for 30 minutes" to AutocompleteSnoozeService.SNOOZE_30_MINUTES,
                "Snooze for 1 hour" to AutocompleteSnoozeService.SNOOZE_1_HOUR,
                "Snooze for 2 hours" to AutocompleteSnoozeService.SNOOZE_2_HOURS,
            ).forEach { (label, duration) ->
                menuItems.add(label)
                actions.add { snoozeService.snoozeAutocomplete(duration) }
            }
        }

        menuItems.add("Recheck API settings")
        actions.add {
            scope.launch {
                isAlive = performHealthCheck()
                updateWidget()
            }
        }

        val status = if (isAlive) "API configured" else "API settings incomplete"
        val popupStep =
            object : BaseListPopupStep<String>("Sweet Autocomplete ($status)", menuItems) {
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

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }

    private suspend fun performHealthCheck(): Boolean =
        withContext(Dispatchers.IO) {
            SweetSettings.getInstance().isOpenAiConfigured
        }
}
