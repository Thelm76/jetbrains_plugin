package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.views.RoundedButton
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class WelcomeScreen(
    private val project: Project,
    private val parentDisposable: Disposable = SweepProjectService.getInstance(project),
) {
    fun create(): JBScrollPane {
        val contentPanel =
            panel {
                row {
                    icon(SweepIcons.BigSweepIcon.scale(80f)).align(AlignX.CENTER)
                }
                row {
                    text("Sweep Autocomplete OSS")
                        .applyToComponent {
                            font = font.deriveFont(java.awt.Font.BOLD, font.size * 1.2f)
                        }.align(AlignX.CENTER)
                }.topGap(TopGap.SMALL)
                row {
                    text(
                        """
                        <div style='text-align: center;'>
                        Local AI code autocomplete powered by<br>
                        llama.cpp with next-edit suggestion models.
                        </div>
                        """.trimIndent(),
                    ).applyToComponent { font = font.deriveFont(font.size * 1.0f) }.align(AlignX.CENTER)
                }
                row {
                    cell(
                        RoundedButton(
                            text = " Start Local Server",
                            parentDisposable = parentDisposable,
                            onClick = {
                                LocalAutocompleteServerManager.getInstance().startServerInTerminal(project)
                            },
                        ).apply {
                            icon = SweepIcons.UserIcon.scale(16f)
                            background = SweepColors.loginButtonColor
                            foreground = SweepColors.textOnPrimary
                            font = font.deriveFont(font.size * 1.2f)
                            border = JBUI.Borders.empty(6, 24)
                        },
                    ).align(AlignX.CENTER)
                }.topGap(TopGap.SMALL)
                row {
                    cell(
                        RoundedButton(
                            text = " Configure Settings",
                            parentDisposable = parentDisposable,
                            onClick = {
                                SweepConfig.getInstance(project).showConfigPopup()
                            },
                        ).apply {
                            font = font.deriveFont(font.size * 1.0f)
                            border = JBUI.Borders.empty(4, 16)
                        },
                    ).align(AlignX.CENTER)
                }.topGap(TopGap.SMALL)

                row {
                    val installHint = if (System.getProperty("os.name").lowercase().contains("mac")) {
                        "Install llama.cpp: <code>brew install llama.cpp</code>"
                    } else {
                        "Install llama.cpp from <a href='https://github.com/ggml-org/llama.cpp#build'>github.com/ggml-org/llama.cpp</a>"
                    }
                    text(
                        """
                        <div style='text-align: center; font-size: 0.95em;'>
                        <b>Quick start:</b><br>
                        1. $installHint<br>
                        2. Click "Start Local Server" above<br>
                        3. Start editing code — suggestions appear automatically<br>
                        <br>
                        Choose model size and runtime in Settings.<br>
                        </div>
                        """.trimIndent(),
                    ).align(AlignX.CENTER)
                }.topGap(TopGap.MEDIUM)
            }

        // Center the content panel both horizontally and vertically
        val containerPanel =
            JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints()
                gbc.gridx = 0
                gbc.gridy = 0
                gbc.weightx = 1.0
                gbc.weighty = 1.0
                gbc.anchor = GridBagConstraints.CENTER
                gbc.insets = JBUI.insets(0, 0, 40, 0)
                add(contentPanel, gbc)
            }

        return JBScrollPane(containerPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = null
        }
    }
}
