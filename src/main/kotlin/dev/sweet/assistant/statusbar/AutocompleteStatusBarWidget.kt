package dev.sweet.assistant.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBList
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.intellij.vcsUtil.showAbove
import dev.sweet.assistant.services.AutocompleteRequestStatusService
import dev.sweet.assistant.settings.SweetSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import kotlin.math.max

class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweetAutocompleteStatus"
        private const val CHOOSE_ACTION = "choose"
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
        val settings = SweetSettings.getInstance()
        val menuItems =
            listOf(
                PopupMenuItem(
                    text =
                        if (settings.automaticAutocompleteOn) {
                            "Switch to Hotkey Only"
                        } else {
                            "Switch to Automatic"
                        },
                    action = {
                        settings.automaticAutocompleteOn = !settings.automaticAutocompleteOn
                        updateWidget()
                    },
                ),
            )

        val list =
            JBList(menuItems).apply {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                selectedIndex = 0
                visibleRowCount = menuItems.size
                fixedCellHeight = JBUI.scale(28)
                cellRenderer = PopupMenuItemRenderer()
            }

        var popup: JBPopup? = null

        fun chooseSelectedItem() {
            list.selectedValue?.action?.invoke()
            popup?.cancel()
        }

        list.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    list
                        .locationToIndex(event.point)
                        .takeIf { it >= 0 }
                        ?.let { list.selectedIndex = it }
                }
            },
        )
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (list.locationToIndex(event.point) >= 0) {
                        chooseSelectedItem()
                    }
                }
            },
        )
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), CHOOSE_ACTION)
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), CHOOSE_ACTION)
        list.actionMap.put(
            CHOOSE_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) = chooseSelectedItem()
            },
        )

        val title =
            JLabel("Sweet Autocomplete").apply {
                border = JBUI.Borders.empty(6, 8, 4, 8)
            }
        val preferredWidth = max(title.preferredSize.width, list.preferredSize.width)
        list.preferredSize = Dimension(preferredWidth, list.preferredSize.height)

        val content =
            JPanel(BorderLayout()).apply {
                add(title, BorderLayout.NORTH)
                add(list, BorderLayout.CENTER)
            }

        popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, list)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .createPopup()
        popup.showAbove(event.component)
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

    private data class PopupMenuItem(
        val text: String,
        val action: () -> Unit,
    )

    private class PopupMenuItemRenderer : ListCellRenderer<PopupMenuItem> {
        override fun getListCellRendererComponent(
            list: JList<out PopupMenuItem>,
            value: PopupMenuItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component =
            JLabel(value.text).apply {
                border = JBUI.Borders.empty(0, 8)
                font = list.font
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
    }
}
