package dev.sweet.assistant.autocomplete.edit

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle

class AutocompleteLoadingRenderer(
    private val editor: Editor,
) : EditorCustomElementRenderer {
    private val icon = AnimatedIcon.Default.INSTANCE

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = icon.iconWidth + JBUI.scale(6)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val x = targetRegion.x + JBUI.scale(3)
        val y = targetRegion.y + (targetRegion.height - icon.iconHeight) / 2
        icon.paintIcon(editor.contentComponent, g, x, y)
    }
}
