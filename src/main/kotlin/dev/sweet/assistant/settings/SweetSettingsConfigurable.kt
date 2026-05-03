package dev.sweet.assistant.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.sweet.assistant.autocomplete.edit.TriggerEditCompletionAction
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
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class SweetSettingsConfigurable(
    @Suppress("unused") private val project: Project,
) : Configurable {
    private val settings = SweetSettings.getInstance()
    private var autocompleteEnabled: JCheckBox? = null
    private var automaticAutocomplete: JCheckBox? = null
    private var acceptWordOnRightArrow: JCheckBox? = null
    private var disableConflictingPlugins: JCheckBox? = null
    private var showAutocompleteBadge: JCheckBox? = null
    private var debounceMs: JSpinner? = null
    private var exclusionPatterns: JTextField? = null
    private var openAiBaseUrl: JTextField? = null
    private var openAiProxy: JTextField? = null
    private var openAiApiKey: JPasswordField? = null
    private var openAiModel: JTextField? = null
    private var openAiTitle: JTextField? = null
    private var openAiMaxTokens: JSpinner? = null
    private var openAiTemperature: JSpinner? = null
    private var openAiRequestTimeoutMs: JSpinner? = null

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
        acceptWordOnRightArrow = JCheckBox("Accept next suggested word with Ctrl+Right Arrow", settings.acceptWordOnRightArrow)
        disableConflictingPlugins = JCheckBox("Automatically disable conflicting full-line completion", settings.disableConflictingPlugins)
        showAutocompleteBadge = JCheckBox("Show autocomplete accept hint", settings.showAutocompleteBadge)

        debounceMs = compactSpinner(SpinnerNumberModel(settings.autocompleteDebounceMs.toInt(), 10, 1000, 10), "#")
        openAiBaseUrl = wideTextField(settings.openAiBaseUrl, "https://openrouter.ai/api/v1")
        openAiProxy = wideTextField(settings.openAiProxy, "http://127.0.0.1:8080")
        openAiApiKey =
            JPasswordField(settings.openAiApiKey).apply {
                preferredSize = JBUI.size(420, preferredSize.height)
                minimumSize = JBUI.size(260, preferredSize.height)
                toolTipText = "API key sent as Bearer token"
            }
        openAiModel = wideTextField(settings.openAiModel, "gpt-4o-mini")
        openAiTitle = wideTextField(settings.openAiTitle, "Sweet Autocomplete Adapter")
        openAiMaxTokens = compactSpinner(SpinnerNumberModel(settings.openAiMaxTokens, 1, 128000, 1), "#")
        openAiTemperature = compactSpinner(SpinnerNumberModel(settings.openAiTemperature, 0.0, 2.0, 0.1), "0.0")
        openAiRequestTimeoutMs = compactSpinner(SpinnerNumberModel(settings.openAiRequestTimeoutMs, 1000, 600000, 1000), "#")
        exclusionPatterns =
            JBTextField(settings.autocompleteExclusionPatterns.joinToString(", ")).apply {
                preferredSize = JBUI.size(420, preferredSize.height)
                minimumSize = JBUI.size(260, preferredSize.height)
                emptyText.text = ".env, .pem, node_modules/"
                toolTipText =
                    "Comma-separated suffixes and path prefixes. Examples: .env, .pem, node_modules/, build/"
            }

        form.add(sectionLabel("OpenAI-compatible API"))
        form.add(labeledRow("Base URL:", openAiBaseUrl!!))
        form.add(labeledRow("Proxy URL:", openAiProxy!!))
        form.add(hintLabel("Leave proxy empty to connect directly. Use host:port or http://host:port."))
        form.add(labeledRow("API key:", openAiApiKey!!))
        form.add(labeledRow("Model:", openAiModel!!))
        form.add(labeledRow("Request X-Title:", openAiTitle!!))
        form.add(labeledRow("Max output tokens:", openAiMaxTokens!!))
        form.add(labeledRow("Temperature:", openAiTemperature!!))
        form.add(labeledRow("Timeout, ms:", openAiRequestTimeoutMs!!))
        form.add(Box.createVerticalStrut(10))

        form.add(sectionLabel("Behavior"))
        form.add(autocompleteEnabled!!)
        form.add(automaticAutocomplete!!)
        form.add(hintLabel(automaticAutocompleteHint()))
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

    private fun wideTextField(
        value: String,
        placeholder: String,
    ): JBTextField =
        JBTextField(value).apply {
            preferredSize = JBUI.size(420, preferredSize.height)
            minimumSize = JBUI.size(260, preferredSize.height)
            emptyText.text = placeholder
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

    private fun automaticAutocompleteHint(): String {
        val action = ActionManager.getInstance().getAction(TriggerEditCompletionAction.ACTION_ID)
        val shortcutText = action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }.takeUnless { it.isNullOrBlank() } ?: "Alt+I"
        return "When disabled, request suggestions manually with $shortcutText. To change it, search Keymap for \"Trigger Autocomplete Suggestion\"."
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
            (debounceMs?.value as? Int)?.toLong() != settings.autocompleteDebounceMs ||
            openAiBaseUrl?.text?.trim() != settings.openAiBaseUrl ||
            openAiProxy?.text?.trim() != settings.openAiProxy ||
            passwordText() != settings.openAiApiKey ||
            openAiModel?.text?.trim() != settings.openAiModel ||
            openAiTitle?.text?.trim() != settings.openAiTitle ||
            (openAiMaxTokens?.value as? Int) != settings.openAiMaxTokens ||
            (openAiTemperature?.value as? Double) != settings.openAiTemperature ||
            (openAiRequestTimeoutMs?.value as? Int) != settings.openAiRequestTimeoutMs ||
            parsePatterns(exclusionPatterns?.text.orEmpty()) != settings.autocompleteExclusionPatterns

    override fun apply() {
        settings.nextEditPredictionFlagOn = autocompleteEnabled?.isSelected ?: settings.nextEditPredictionFlagOn
        settings.automaticAutocompleteOn = automaticAutocomplete?.isSelected ?: settings.automaticAutocompleteOn
        settings.acceptWordOnRightArrow = acceptWordOnRightArrow?.isSelected ?: settings.acceptWordOnRightArrow
        settings.disableConflictingPlugins = disableConflictingPlugins?.isSelected ?: settings.disableConflictingPlugins
        settings.showAutocompleteBadge = showAutocompleteBadge?.isSelected ?: settings.showAutocompleteBadge
        settings.autocompleteDebounceMs = ((debounceMs?.value as? Int) ?: settings.autocompleteDebounceMs.toInt()).toLong()
        settings.openAiBaseUrl = openAiBaseUrl?.text.orEmpty()
        settings.openAiProxy = openAiProxy?.text.orEmpty()
        settings.openAiApiKey = passwordText()
        settings.openAiModel = openAiModel?.text.orEmpty()
        settings.openAiTitle = openAiTitle?.text.orEmpty()
        settings.openAiMaxTokens = openAiMaxTokens?.value as? Int ?: settings.openAiMaxTokens
        settings.openAiTemperature = openAiTemperature?.value as? Double ?: settings.openAiTemperature
        settings.openAiRequestTimeoutMs = openAiRequestTimeoutMs?.value as? Int ?: settings.openAiRequestTimeoutMs
        settings.autocompleteExclusionPatterns = parsePatterns(exclusionPatterns?.text.orEmpty()).toMutableSet()
    }

    override fun reset() {
        autocompleteEnabled?.isSelected = settings.nextEditPredictionFlagOn
        automaticAutocomplete?.isSelected = settings.automaticAutocompleteOn
        acceptWordOnRightArrow?.isSelected = settings.acceptWordOnRightArrow
        disableConflictingPlugins?.isSelected = settings.disableConflictingPlugins
        showAutocompleteBadge?.isSelected = settings.showAutocompleteBadge
        debounceMs?.value = settings.autocompleteDebounceMs.toInt()
        openAiBaseUrl?.text = settings.openAiBaseUrl
        openAiProxy?.text = settings.openAiProxy
        openAiApiKey?.text = settings.openAiApiKey
        openAiModel?.text = settings.openAiModel
        openAiTitle?.text = settings.openAiTitle
        openAiMaxTokens?.value = settings.openAiMaxTokens
        openAiTemperature?.value = settings.openAiTemperature
        openAiRequestTimeoutMs?.value = settings.openAiRequestTimeoutMs
        exclusionPatterns?.text = settings.autocompleteExclusionPatterns.joinToString(", ")
    }

    override fun getDisplayName(): String = "Sweet Autocomplete"

    private fun parsePatterns(value: String): Set<String> =
        value
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { setOf(".env") }

    private fun passwordText(): String = String(openAiApiKey?.password ?: CharArray(0)).trim()
}
