package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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
            JPanel(GridBagLayout()).apply {
                isOpaque = false
            }

        autocompleteEnabled = JCheckBox("Enable autocomplete", settings.nextEditPredictionFlagOn)
        automaticAutocomplete = JCheckBox("Request suggestions automatically while typing", settings.automaticAutocompleteOn)
        acceptWordOnRightArrow = JCheckBox("Accept next suggested word with Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPlugins = JCheckBox("Automatically disable conflicting full-line completion", settings.disableConflictingPlugins)
        showAutocompleteBadge = JCheckBox("Show autocomplete accept hint", settings.showAutocompleteBadge)

        localPort = compactSpinner(SpinnerNumberModel(settings.autocompleteLocalPort, 1, 65535, 1))
        debounceMs = compactSpinner(SpinnerNumberModel(settings.autocompleteDebounceMs.toInt(), 10, 1000, 10))
        exclusionPatterns =
            JBTextField(settings.autocompleteExclusionPatterns.joinToString(", ")).apply {
                preferredSize = JBUI.size(420, preferredSize.height)
                minimumSize = JBUI.size(260, preferredSize.height)
                emptyText.text = ".env, .pem, node_modules/"
                toolTipText =
                    "Comma-separated suffixes and path prefixes. Examples: .env, .pem, node_modules/, build/"
            }

        var row = 0
        row = addSection(form, row, "Local server")
        row = addField(form, row, "Port:", localPort!!)
        row = addGap(form, row, 10)

        row = addSection(form, row, "Behavior")
        listOf(
            autocompleteEnabled,
            automaticAutocomplete,
            acceptWordOnRightArrow,
            disableConflictingPlugins,
            showAutocompleteBadge,
        ).forEach {
            row = addCheckBox(form, row, it!!)
        }
        row = addField(form, row, "Debounce, ms:", debounceMs!!)
        row = addGap(form, row, 10)

        row = addSection(form, row, "Excluded files")
        row = addField(form, row, "Patterns:", exclusionPatterns!!)
        row =
            addHint(
                form,
                row,
                "Use comma-separated suffixes and path prefixes. Examples: .env, .pem, node_modules/, build/",
            )
        addVerticalFiller(form, row)

        panel.add(form, BorderLayout.NORTH)
        return panel
    }

    private fun compactSpinner(model: SpinnerNumberModel): JSpinner =
        JSpinner(model).apply {
            preferredSize = JBUI.size(96, preferredSize.height)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

    private fun addSection(
        form: JPanel,
        row: Int,
        text: String,
    ): Int {
        val label =
            JLabel(text).apply {
                font = font.deriveFont(font.style or Font.BOLD)
            }
        form.add(
            label,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 8, 0)
            },
        )
        return row + 1
    }

    private fun addCheckBox(
        form: JPanel,
        row: Int,
        checkBox: JCheckBox,
    ): Int {
        form.add(
            checkBox,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = Insets(0, 0, 6, 0)
            },
        )
        return row + 1
    }

    private fun addField(
        form: JPanel,
        row: Int,
        label: String,
        component: JComponent,
    ): Int {
        form.add(
            JLabel(label),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 6, 12)
            },
        )
        form.add(
            component,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = Insets(0, 0, 6, 0)
            },
        )
        return row + 1
    }

    private fun addHint(
        form: JPanel,
        row: Int,
        text: String,
    ): Int {
        form.add(
            JLabel(text).apply {
                foreground = JBColor.GRAY
            },
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = Insets(0, 0, 10, 0)
            },
        )
        return row + 1
    }

    private fun addGap(
        form: JPanel,
        row: Int,
        height: Int,
    ): Int {
        form.add(
            JPanel().apply {
                isOpaque = false
                preferredSize = Dimension(1, height)
            },
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
            },
        )
        return row + 1
    }

    private fun addVerticalFiller(
        form: JPanel,
        row: Int,
    ) {
        form.add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            },
        )
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
