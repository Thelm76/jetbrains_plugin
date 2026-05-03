package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class SweepSettingsConfigurable(
    @Suppress("unused") private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()
    private var autocompleteEnabled: JCheckBox? = null
    private var automaticAutocomplete: JCheckBox? = null
    private var acceptWordOnRightArrow: JCheckBox? = null
    private var disableConflictingPlugins: JCheckBox? = null
    private var showAutocompleteBadge: JCheckBox? = null
    private var localPort: JSpinner? = null
    private var debounceMs: JSpinner? = null
    private var exclusionPatterns: JTextField? = null

    override fun createComponent(): JComponent {
        val panel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(16)
            }

        val form =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        autocompleteEnabled = JCheckBox("Enable autocomplete", settings.nextEditPredictionFlagOn)
        automaticAutocomplete = JCheckBox("Request suggestions automatically while typing", settings.automaticAutocompleteOn)
        acceptWordOnRightArrow = JCheckBox("Accept next suggested word with Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPlugins = JCheckBox("Automatically disable conflicting full-line completion", settings.disableConflictingPlugins)
        showAutocompleteBadge = JCheckBox("Show autocomplete accept hint", settings.showAutocompleteBadge)

        localPort = compactSpinner(SpinnerNumberModel(settings.autocompleteLocalPort, 1, 65535, 1), "#")
        debounceMs = compactSpinner(SpinnerNumberModel(settings.autocompleteDebounceMs.toInt(), 10, 1000, 10), "#")
        exclusionPatterns =
            JBTextField(settings.autocompleteExclusionPatterns.joinToString(", ")).apply {
                preferredSize = JBUI.size(420, preferredSize.height)
                minimumSize = JBUI.size(260, preferredSize.height)
                emptyText.text = ".env, .pem, node_modules/"
                toolTipText =
                    "Comma-separated suffixes and path prefixes. Examples: .env, .pem, node_modules/, build/"
            }

        // Local server section
        form.add(sectionLabel("Local server"))
        form.add(labeledRow("Port:", localPort!!))
        form.add(Box.createVerticalStrut(10))

        // Behavior section
        form.add(sectionLabel("Behavior"))
        form.add(autocompleteEnabled!!)
        form.add(automaticAutocomplete!!)
        form.add(acceptWordOnRightArrow!!)
        form.add(disableConflictingPlugins!!)
        form.add(showAutocompleteBadge!!)
        form.add(labeledRow("Debounce, ms:", debounceMs!!))
        form.add(Box.createVerticalStrut(10))

        // Excluded files section
        form.add(sectionLabel("Excluded files"))
        form.add(labeledRow("Patterns:", exclusionPatterns!!))
        form.add(hintLabel("Use comma-separated suffixes and path prefixes. Examples: .env, .pem, node_modules/, build/"))

        form.add(Box.createVerticalGlue())

        panel.add(form, BorderLayout.NORTH)
        return panel
    }

    private fun compactSpinner(model: SpinnerNumberModel, format: String = "#"): JSpinner =
        JSpinner(model).apply {
            preferredSize = JBUI.size(96, preferredSize.height)
            minimumSize = preferredSize
            maximumSize = preferredSize
            editor = JSpinner.NumberEditor(this, format)
        }

    private fun sectionLabel(text: String): JComponent =
        JLabel(text).apply {
            font = font.deriveFont(font.style or Font.BOLD)
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

    private fun hintLabel(text: String): JComponent =
        JLabel(text).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(0, 0, 10, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

    private fun labeledRow(label: String, component: JComponent): JComponent {
        val panel =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(6)
            }
        panel.add(JLabel(label).apply { border = JBUI.Borders.emptyRight(12) })
        panel.add(component)
        return panel
    }

    override fun isModified(): Boolean =
        autocompleteEnabled?.isSelected != settings.nextEditPredictionFlagOn ||
            automaticAutocomplete?.isSelected != settings.automaticAutocompleteOn ||
            acceptWordOnRightArrow?.isSelected != settings.acceptWordOnRightArrow ||
            disableConflictingPlugins?.isSelected != settings.disableConflictingPlugins ||
            showAutocompleteBadge?.isSelected != settings.showAutocompleteBadge ||
            (localPort?.value as? Int) != settings.autocompleteLocalPort ||
            (debounceMs?.value as? Int)?.toLong() != settings.autocompleteDebounceMs ||
            parsePatterns(exclusionPatterns?.text.orEmpty()) != settings.autocompleteExclusionPatterns

    override fun apply() {
        settings.nextEditPredictionFlagOn = autocompleteEnabled?.isSelected ?: settings.nextEditPredictionFlagOn
        settings.automaticAutocompleteOn = automaticAutocomplete?.isSelected ?: settings.automaticAutocompleteOn
        settings.acceptWordOnRightArrow = acceptWordOnRightArrow?.isSelected ?: settings.acceptWordOnRightArrow
        settings.disableConflictingPlugins = disableConflictingPlugins?.isSelected ?: settings.disableConflictingPlugins
        settings.showAutocompleteBadge = showAutocompleteBadge?.isSelected ?: settings.showAutocompleteBadge
        settings.autocompleteLocalPort = localPort?.value as? Int ?: settings.autocompleteLocalPort
        settings.autocompleteDebounceMs = ((debounceMs?.value as? Int) ?: settings.autocompleteDebounceMs.toInt()).toLong()
        settings.autocompleteExclusionPatterns = parsePatterns(exclusionPatterns?.text.orEmpty()).toMutableSet()
    }

    override fun reset() {
        autocompleteEnabled?.isSelected = settings.nextEditPredictionFlagOn
        automaticAutocomplete?.isSelected = settings.automaticAutocompleteOn
        acceptWordOnRightArrow?.isSelected = settings.acceptWordOnRightArrow
        disableConflictingPlugins?.isSelected = settings.disableConflictingPlugins
        showAutocompleteBadge?.isSelected = settings.showAutocompleteBadge
        localPort?.value = settings.autocompleteLocalPort
        debounceMs?.value = settings.autocompleteDebounceMs.toInt()
        exclusionPatterns?.text = settings.autocompleteExclusionPatterns.joinToString(", ")
    }

    override fun getDisplayName(): String = "Sweep Autocomplete"

    private fun parsePatterns(value: String): Set<String> =
        value
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { setOf(".env") }
}
