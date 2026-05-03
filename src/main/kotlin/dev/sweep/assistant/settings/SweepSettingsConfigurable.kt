package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
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
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(16)

        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

        autocompleteEnabled = JCheckBox("Enable autocomplete", settings.nextEditPredictionFlagOn)
        automaticAutocomplete = JCheckBox("Request suggestions automatically while typing", settings.automaticAutocompleteOn)
        acceptWordOnRightArrow = JCheckBox("Accept next suggested word with Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPlugins = JCheckBox("Automatically disable conflicting full-line completion", settings.disableConflictingPlugins)
        showAutocompleteBadge = JCheckBox("Show autocomplete accept hint", settings.showAutocompleteBadge)
        localPort = JSpinner(SpinnerNumberModel(settings.autocompleteLocalPort, 1, 65535, 1))
        debounceMs = JSpinner(SpinnerNumberModel(settings.autocompleteDebounceMs.toInt(), 10, 1000, 10))
        exclusionPatterns = JBTextField(settings.autocompleteExclusionPatterns.joinToString(", "))

        content.add(sectionLabel("Local server"))
        content.add(row("Port", localPort!!))
        content.add(Box.createRigidArea(Dimension(0, 12)))
        content.add(sectionLabel("Behavior"))
        listOf(
            autocompleteEnabled,
            automaticAutocomplete,
            acceptWordOnRightArrow,
            disableConflictingPlugins,
            showAutocompleteBadge,
        ).forEach {
            content.add(it)
            content.add(Box.createRigidArea(Dimension(0, 6)))
        }
        content.add(row("Debounce, ms", debounceMs!!))
        content.add(row("Excluded file suffixes/prefixes", exclusionPatterns!!))

        panel.add(content, BorderLayout.NORTH)
        return panel
    }

    private fun sectionLabel(text: String): JLabel =
        JLabel(text).apply {
            border = JBUI.Borders.empty(0, 0, 8, 0)
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }

    private fun row(
        label: String,
        component: JComponent,
    ): JPanel =
        JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(4, 0)
            add(JLabel(label), BorderLayout.WEST)
            add(component, BorderLayout.CENTER)
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
