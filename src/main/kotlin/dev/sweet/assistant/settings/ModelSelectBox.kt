package dev.sweet.assistant.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.AWTEventListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.text.Collator
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ModelSelectBox : JButton() {
    private val searchEdit = JBTextField()
    private val contentPanel = JPanel(BorderLayout())
    private val loadingPanel = JPanel(GridLayout(1, 1))
    private val loadingLabel = JLabel("Loading...", SwingConstants.CENTER)
    private val rowsPanel = RowsPanel()
    private val scrollPane = JScrollPane(rowsPanel)
    private val rowComponents = mutableListOf<ModelRowComponent>()
    private var popupWindow: Window? = null
    private var tooltipWindow: Window? = null
    private var outsideClickListener: AWTEventListener? = null
    private var loadedModels: List<ModelInfo> = emptyList()
    private var visibleEntries: List<ModelListEntry> = emptyList()
    private var selectedModelId: String = ""
    private var noSelectionLabel: String = "Not selected"
    private var selectedIndex: Int = 0
    private var hoveredIndex: Int = -1
    private var popupLoading: Boolean = false
    private var reloadGeneration: Int = 0
    private var tooltipModelId: String? = null
    private var tooltipAnchor: Rectangle? = null
    var modelsReloadHandler: ((ModelSelectBox, Int) -> Unit)? = null
    var currentModelChangedHandler: ((String) -> Unit)? = null

    init {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        preferredSize = JBUI.size(420, preferredSize.height)
        minimumSize = JBUI.size(260, preferredSize.height)
        margin = JBUI.insets(0, 8, 0, 26)
        horizontalAlignment = LEFT
        applyInterfaceFont()
        updateButtonLabel()
        configurePopupContent()
        addActionListener { showPopup() }
    }

    fun currentModel(): String = selectedModelId

    fun setCurrentModel(modelId: String) {
        val normalized = modelId.trim()
        if (selectedModelId == normalized) return
        selectedModelId = normalized
        updateButtonLabel()
        updateCurrentIndexLater()
    }

    fun setEmptyLabel(label: String) {
        val normalized = label.trim().ifEmpty { "Not selected" }
        if (noSelectionLabel == normalized) return
        noSelectionLabel = normalized
        updateButtonLabel()
        if (!popupLoading) rebuildRows()
    }

    fun currentReloadGeneration(): Int = reloadGeneration

    fun setModels(models: List<ModelInfo>) {
        loadedModels = models.sortedWith { left, right -> modelCollator.compare(displayNameFor(left), displayNameFor(right)) }
        popupLoading = false
        val popup = popupWindow
        if (popup == null || !popup.isVisible) return

        searchEdit.isEnabled = true
        rebuildRows()
        showListPage()
        searchEdit.requestFocus()
        searchEdit.selectAll()
        updateCurrentIndexLater()
    }

    fun setLoadError(message: String) {
        popupLoading = false
        val popup = popupWindow
        if (popup == null || !popup.isVisible) return

        searchEdit.isEnabled = true
        loadingLabel.text = message.trim().ifEmpty { "Failed to load models." }
        showLoadingPage()
        searchEdit.requestFocus()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = foreground
            val x = width - JBUI.scale(14)
            val y = height / 2 - JBUI.scale(2)
            g2.fillPolygon(
                intArrayOf(x - JBUI.scale(4), x + JBUI.scale(4), x),
                intArrayOf(y, y, y + JBUI.scale(5)),
                3,
            )
        } finally {
            g2.dispose()
        }
    }

    private fun configurePopupContent() {
        contentPanel.border = JBUI.Borders.customLine(JBColor.border(), 1)
        contentPanel.background = Color.WHITE

        searchEdit.emptyText.text = "Search models"
        searchEdit.background = Color.WHITE
        searchEdit.font = interfaceFont()
        searchEdit.border = JBUI.Borders.empty(6, 8)
        searchEdit.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = rebuildRows()

                override fun removeUpdate(e: DocumentEvent) = rebuildRows()

                override fun changedUpdate(e: DocumentEvent) = rebuildRows()
            },
        )
        searchEdit.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    val opposite = e.oppositeComponent
                    val oppositePoint = opposite?.let { runCatching { it.locationOnScreen }.getOrNull() }
                    if (oppositePoint != null && isInsidePopupOrTooltip(oppositePoint)) return
                    closePopupIfFocusMovedOutside()
                }
            },
        )
        bindPopupKeys(searchEdit)

        loadingPanel.isOpaque = false
        loadingLabel.font = interfaceFont()
        loadingPanel.add(loadingLabel)

        rowsPanel.layout = javax.swing.BoxLayout(rowsPanel, javax.swing.BoxLayout.Y_AXIS)
        rowsPanel.font = interfaceFont()
        rowsPanel.background = Color.WHITE
        bindPopupKeys(rowsPanel)

        scrollPane.border = null
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBar.unitIncrement = ROW_HEIGHT
        scrollPane.background = Color.WHITE
        scrollPane.viewport.background = Color.WHITE
        bindPopupKeys(scrollPane)
        bindPopupKeys(scrollPane.viewport as JComponent)

        installWheelHandling(contentPanel)
        installWheelHandling(searchEdit)
        installWheelHandling(rowsPanel)
        installWheelHandling(scrollPane)
        installWheelHandling(scrollPane.viewport)
    }

    private fun bindPopupKeys(component: JComponent) {
        val inputMap = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = component.actionMap
        inputMap.put(KeyStroke.getKeyStroke("DOWN"), "modelSelectDown")
        inputMap.put(KeyStroke.getKeyStroke("UP"), "modelSelectUp")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "modelSelectEnter")
        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "modelSelectEscape")
        actionMap.put(
            "modelSelectDown",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = moveCurrentIndex(1)
            },
        )
        actionMap.put(
            "modelSelectUp",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = moveCurrentIndex(-1)
            },
        )
        actionMap.put(
            "modelSelectEnter",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = activateCurrentIndex()
            },
        )
        actionMap.put(
            "modelSelectEscape",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = hidePopup()
            },
        )
    }

    private fun installWheelHandling(component: java.awt.Component) {
        component.addMouseWheelListener { event ->
            if (popupWindow?.isVisible != true || popupLoading) return@addMouseWheelListener
            scrollByWheel(event)
            event.consume()
        }
    }

    private fun scrollByWheel(event: MouseWheelEvent) {
        val scrollBar = scrollPane.verticalScrollBar
        val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum)
        scrollBar.value = (scrollBar.value + event.unitsToScroll * scrollBar.unitIncrement).coerceIn(scrollBar.minimum, maxValue)
    }

    private fun showPopup() {
        hideTooltip()
        hidePopupWindowOnly()
        loadedModels = emptyList()
        visibleEntries = emptyList()
        rowsPanel.removeAll()
        searchEdit.text = ""
        showLoading()

        val owner = SwingUtilities.getWindowAncestor(this)
        val popup =
            JWindow(owner).apply {
                background = Color(0, 0, 0, 0)
                layout = BorderLayout()
                add(contentPanel, BorderLayout.CENTER)
                focusableWindowState = true
                addWindowFocusListener(
                    object : WindowAdapter() {
                        override fun windowLostFocus(e: WindowEvent) = closePopupIfFocusMovedOutside()
                    },
                )
            }
        popupWindow = popup
        val origin = locationOnScreen
        popup.setBounds(origin.x, origin.y, width, POPUP_HEIGHT)
        popup.isVisible = true
        installOutsideClickListener()
        popup.toFront()
        SwingUtilities.invokeLater {
            if (popupWindow === popup && popup.isVisible) {
                popup.requestFocus()
                searchEdit.requestFocus()
                searchEdit.selectAll()
            }
        }

        val generation = ++reloadGeneration
        SwingUtilities.invokeLater {
            if (popupWindow === popup && popup.isVisible && reloadGeneration == generation) {
                modelsReloadHandler?.invoke(this, generation)
            }
        }
    }

    private fun showLoading() {
        popupLoading = true
        searchEdit.isEnabled = false
        loadingLabel.text = "Loading..."
        showLoadingPage()
    }

    private fun showLoadingPage() {
        contentPanel.removeAll()
        contentPanel.add(searchEdit, BorderLayout.NORTH)
        contentPanel.add(loadingPanel, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun showListPage() {
        contentPanel.removeAll()
        contentPanel.add(searchEdit, BorderLayout.NORTH)
        contentPanel.add(scrollPane, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun rebuildRows() {
        if (popupLoading) return

        val filterText = searchEdit.text.trim().lowercase(Locale.getDefault())
        val filteredModels =
            loadedModels.filter { model ->
                filterText.isEmpty() ||
                    displayNameFor(model).lowercase(Locale.getDefault()).contains(filterText) ||
                    model.name.lowercase(Locale.getDefault()).contains(filterText)
            }

        visibleEntries = listOf(ModelListEntry(noSelectionLabel, null)) + filteredModels.map { ModelListEntry(displayNameFor(it), it) }
        rowsPanel.removeAll()
        rowComponents.clear()
        visibleEntries.forEachIndexed { index, entry ->
            val row = ModelRowComponent(index, entry)
            rowComponents += row
            rowsPanel.add(row)
        }
        rowsPanel.preferredSize = Dimension(scrollPane.viewport.width.coerceAtLeast(width), visibleEntries.size * ROW_HEIGHT)
        rowsPanel.revalidate()
        rowsPanel.repaint()
        hideTooltip()
        updateCurrentIndexLater()
    }

    private fun chooseModel(modelId: String) {
        hideTooltip()
        hidePopup()
        if (selectedModelId == modelId) return

        selectedModelId = modelId
        updateButtonLabel()
        currentModelChangedHandler?.invoke(selectedModelId)
    }

    private fun updateButtonLabel() {
        text = selectedModelId.ifEmpty { noSelectionLabel }
        toolTipText = text
    }

    private fun applyInterfaceFont() {
        font = interfaceFont()
    }

    private fun interfaceFont(): Font = javax.swing.UIManager.getFont("Label.font") ?: font

    private fun updateCurrentIndex() {
        if (popupWindow?.isVisible != true || visibleEntries.isEmpty()) return

        selectedIndex = visibleEntries.indexOfFirst { it.model?.id.orEmpty() == selectedModelId }.takeIf { it >= 0 } ?: 0
        repaintRows()
        centerIndex(selectedIndex)
    }

    private fun updateCurrentIndexLater() {
        SwingUtilities.invokeLater {
            if (popupWindow?.isVisible == true) updateCurrentIndex()
        }
        Timer(25) {
            if (popupWindow?.isVisible == true) updateCurrentIndex()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun moveCurrentIndex(delta: Int) {
        if (popupWindow?.isVisible != true || visibleEntries.isEmpty()) return

        selectedIndex = (selectedIndex + delta).coerceIn(0, visibleEntries.lastIndex)
        repaintRows()
        ensureIndexVisible(selectedIndex)
        hideTooltip()
    }

    private fun activateCurrentIndex() {
        if (popupWindow?.isVisible != true || selectedIndex !in visibleEntries.indices) return
        chooseModel(visibleEntries[selectedIndex].model?.id.orEmpty())
    }

    private fun centerIndex(index: Int) {
        val viewportRows = (scrollPane.viewport.height / ROW_HEIGHT).coerceAtLeast(1)
        val centeredRow = (index - viewportRows / 2).coerceAtLeast(0)
        scrollPane.verticalScrollBar.value = centeredRow * ROW_HEIGHT
    }

    private fun ensureIndexVisible(index: Int) {
        val rowRect = Rectangle(0, index * ROW_HEIGHT, rowsPanel.width.coerceAtLeast(1), ROW_HEIGHT)
        rowsPanel.scrollRectToVisible(rowRect)
    }

    private fun repaintRows() {
        rowComponents.forEach { it.repaint() }
    }

    private fun setHoveredIndex(index: Int) {
        if (hoveredIndex == index) return
        hoveredIndex = index
        repaintRows()
    }

    private fun showTooltipFor(
        model: ModelInfo,
        anchor: Rectangle,
    ) {
        if (tooltipModelId == model.id && tooltipAnchor == anchor && tooltipWindow?.isVisible == true) return
        hideTooltip()

        val panel =
            JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                background = TOOLTIP_BACKGROUND
                border = BorderFactory.createCompoundBorder(LineBorder(TOOLTIP_BORDER), JBUI.Borders.empty(6, 8))
                add(tooltipLabel(richFieldText("Name", model.name), true))
                add(tooltipLabel(richFieldText("Description", model.description), false))
                add(tooltipLabel(richFieldText("Pricing", pricingValueFor(model)), false))
                add(tooltipLabel(richFieldText("Knowledge cutoff", model.knowledgeCutoff), false))
            }

        val tooltip =
            Window(popupWindow).apply {
                type = Window.Type.POPUP
                focusableWindowState = false
                layout = BorderLayout()
                add(panel, BorderLayout.CENTER)
                pack()
            }

        val screen = graphicsConfiguration.bounds
        var x = anchor.x + anchor.width + JBUI.scale(8)
        var y = anchor.y
        if (x + tooltip.width > screen.x + screen.width) x = anchor.x - tooltip.width - JBUI.scale(8)
        if (y + tooltip.height > screen.y + screen.height) y = screen.y + screen.height - tooltip.height
        y = y.coerceAtLeast(screen.y)

        tooltip.setLocation(x, y)
        tooltip.isVisible = true
        tooltipWindow = tooltip
        tooltipModelId = model.id
        tooltipAnchor = Rectangle(anchor)
    }

    private fun hideTooltip() {
        tooltipWindow?.isVisible = false
        tooltipWindow?.dispose()
        tooltipWindow = null
        tooltipModelId = null
        tooltipAnchor = null
    }

    private fun tooltipLabel(
        html: String,
        bold: Boolean,
    ): JLabel =
        JLabel("<html><body width='${TOOLTIP_WIDTH - 24}'>$html</body></html>").apply {
            foreground = Color(0x20, 0x20, 0x20)
            background = TOOLTIP_BACKGROUND
            isOpaque = false
            font = interfaceFont()
            if (bold) font = font.deriveFont(Font.BOLD)
        }

    private fun hidePopup() {
        hideTooltip()
        hidePopupWindowOnly()
        popupLoading = false
        reloadGeneration++
    }

    private fun hidePopupWindowOnly() {
        uninstallOutsideClickListener()
        popupWindow?.isVisible = false
        popupWindow?.dispose()
        popupWindow = null
    }

    private fun installOutsideClickListener() {
        uninstallOutsideClickListener()
        val listener =
            AWTEventListener { event ->
                if (event !is MouseEvent || event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                val point = MouseInfo.getPointerInfo()?.location ?: return@AWTEventListener
                if (!isInsidePopupOrTooltip(point) && !boundsOnScreen().contains(point)) {
                    hidePopup()
                }
            }
        outsideClickListener = listener
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    }

    private fun uninstallOutsideClickListener() {
        outsideClickListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        outsideClickListener = null
    }

    private fun closePopupIfFocusMovedOutside() {
        SwingUtilities.invokeLater {
            if (popupWindow?.isVisible != true) return@invokeLater
            val focusOwner =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    ?: return@invokeLater hidePopup()
            val point =
                runCatching { focusOwner.locationOnScreen }.getOrNull()
                    ?: return@invokeLater hidePopup()
            if (!isInsidePopupOrTooltip(point) && !boundsOnScreen().contains(point)) hidePopup()
        }
    }

    private fun isInsidePopupOrTooltip(point: Point): Boolean =
        popupWindow?.bounds?.contains(point) == true || tooltipWindow?.bounds?.contains(point) == true

    private fun boundsOnScreen(): Rectangle {
        val point = locationOnScreen
        return Rectangle(point.x, point.y, width, height)
    }

    private fun pricingValueFor(model: ModelInfo): String {
        val parts =
            listOfNotNull(
                model.promptPrice.trim().takeIf { it.isNotEmpty() }?.let { "Prompt: $it" },
                model.completionPrice.trim().takeIf { it.isNotEmpty() }?.let { "Completion: $it" },
                model.inputCacheReadPrice.trim().takeIf { it.isNotEmpty() }?.let { "Cache read: $it" },
            )
        return parts.ifEmpty { listOf("N/A") }.joinToString("; ")
    }

    private fun richFieldText(
        label: String,
        value: String,
    ): String = "<b>${escapeHtml(label)}:</b> ${escapeHtml(value.trim().ifEmpty { "N/A" })}"

    private fun displayNameFor(model: ModelInfo): String = model.id.ifEmpty { model.name }

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private data class ModelListEntry(
        val text: String,
        val model: ModelInfo?,
    )

    private inner class ModelRowComponent(
        private val rowIndex: Int,
        private val entry: ModelListEntry,
    ) : JComponent() {
        init {
            preferredSize = Dimension(1, ROW_HEIGHT)
            minimumSize = Dimension(1, ROW_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, ROW_HEIGHT)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) = setHoveredIndex(rowIndex)

                    override fun mouseExited(e: MouseEvent) {
                        setHoveredIndex(-1)
                        hideTooltip()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        selectedIndex = rowIndex
                        repaintRows()
                        if (isInfoArea(e.point)) {
                            showInfoTooltip()
                        } else {
                            chooseModel(entry.model?.id.orEmpty())
                        }
                    }
                },
            )
            addMouseMotionListener(
                object : MouseAdapter() {
                    override fun mouseMoved(e: MouseEvent) {
                        setHoveredIndex(rowIndex)
                        if (isInfoArea(e.point)) {
                            showInfoTooltip()
                        } else {
                            hideTooltip()
                        }
                    }
                },
            )
            installWheelHandling(this)
            bindPopupKeys(this)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val selected = rowIndex == selectedIndex
                val hovered = rowIndex == hoveredIndex
                when {
                    selected -> {
                        g2.color = UI_SELECTION_BACKGROUND
                        g2.fillRect(0, 0, width, height)
                    }
                    hovered -> {
                        g2.color = UI_HOVER_BACKGROUND
                        g2.fillRect(0, 0, width, height)
                    }
                    else -> {
                        g2.color = rowsPanel.background
                        g2.fillRect(0, 0, width, height)
                    }
                }

                val isEmpty = entry.model == null
                val textColor =
                    when {
                        selected -> UI_SELECTION_FOREGROUND
                        isEmpty -> JBColor.GRAY
                        else -> foreground
                    }
                g2.color = textColor
                g2.font = font
                val metrics = g2.fontMetrics
                val rightInset = if (isEmpty) JBUI.scale(8) else JBUI.scale(38)
                val textWidth = (width - JBUI.scale(8) - rightInset).coerceAtLeast(1)
                val text = metrics.elidedText(entry.text, textWidth)
                val baseline = ((height - metrics.height) / 2) + metrics.ascent
                g2.drawString(text, JBUI.scale(8), baseline)

                if (!isEmpty) {
                    val info = infoRect()
                    g2.color = if (selected) UI_SELECTION_FOREGROUND else JBColor.namedColor("Label.disabledForeground", Color.GRAY)
                    g2.drawOval(info.x, info.y, info.width, info.height)
                    g2.font = font.deriveFont(Font.BOLD)
                    val infoMetrics = g2.fontMetrics
                    val question = "?"
                    g2.drawString(
                        question,
                        info.x + (info.width - infoMetrics.stringWidth(question)) / 2,
                        info.y + ((info.height - infoMetrics.height) / 2) + infoMetrics.ascent,
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        private fun isInfoArea(point: Point): Boolean = entry.model != null && infoRect().contains(point)

        private fun showInfoTooltip() {
            val model = entry.model ?: return
            val info = infoRect()
            val location = locationOnScreen
            showTooltipFor(model, Rectangle(location.x + info.x, location.y + info.y, info.width, info.height))
        }

        private fun infoRect(): Rectangle =
            Rectangle(width - JBUI.scale(30), (height - JBUI.scale(18)) / 2, JBUI.scale(18), JBUI.scale(18))
    }

    private class RowsPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = ROW_HEIGHT

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = (visibleRect.height - ROW_HEIGHT).coerceAtLeast(ROW_HEIGHT)

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        private val modelCollator: Collator =
            Collator.getInstance().apply {
                strength = Collator.PRIMARY
            }
        private val ROW_HEIGHT = JBUI.scale(30)
        private val POPUP_HEIGHT = JBUI.scale(300)
        private val TOOLTIP_WIDTH = JBUI.scale(320)
        private val TOOLTIP_BACKGROUND = Color(0xff, 0xff, 0xe1)
        private val TOOLTIP_BORDER = Color(0x76, 0x76, 0x76)
        private val UI_SELECTION_BACKGROUND = JBColor.namedColor("List.selectionBackground", Color(0x38, 0x75, 0xd6))
        private val UI_SELECTION_FOREGROUND = JBColor.namedColor("List.selectionForeground", Color.WHITE)
        private val UI_HOVER_BACKGROUND = JBColor.namedColor("List.hoverBackground", Color(0xea, 0xea, 0xea))
    }
}

private fun java.awt.FontMetrics.elidedText(
    value: String,
    maxWidth: Int,
): String {
    if (stringWidth(value) <= maxWidth) return value
    val ellipsis = "..."
    val ellipsisWidth = stringWidth(ellipsis)
    if (ellipsisWidth >= maxWidth) return ellipsis

    var end = value.length
    while (end > 0 && stringWidth(value.substring(0, end)) + ellipsisWidth > maxWidth) {
        end--
    }
    return value.substring(0, end) + ellipsis
}
