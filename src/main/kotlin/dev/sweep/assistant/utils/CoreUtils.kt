package dev.sweep.assistant.utils

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.io.File
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.KeyStroke

val defaultJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

fun <T> encodeString(
    request: T,
    serializer: SerializationStrategy<T>,
): String = defaultJson.encodeToString(serializer, request)

fun <T> HttpResponse<T>.raiseForStatus(): HttpResponse<T> {
    if (statusCode() !in 200..399) {
        throw java.io.IOException("HTTP ${statusCode()}")
    }
    return this
}

class EvictingQueue<T>(
    private val maxSize: Int,
) : ConcurrentLinkedQueue<T>() {
    override fun add(element: T): Boolean {
        val result = super.add(element)
        evict()
        return result
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val result = super.addAll(elements)
        evict()
        return result
    }

    fun replaceLast(element: T): Boolean {
        if (isEmpty()) return false
        remove(last())
        return add(element)
    }

    private fun evict() {
        while (size > maxSize) {
            poll()
        }
    }
}

class LRUCache<K, V>(
    private val maxSize: Int,
    private val ttlMs: Long,
) {
    private data class Entry<V>(
        val value: V,
        val timestamp: Long,
    )

    private val map =
        object : LinkedHashMap<K, Entry<V>>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean = size > maxSize
        }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        map[key] = Entry(value, System.currentTimeMillis())
    }
}

val Int.scaled: Int get() = JBUI.scale(this)
val Dimension.scaled: Dimension get() = Dimension(JBUI.scale(width), JBUI.scale(height))

val Project.osBasePath: String get() = basePath ?: ""

fun Color.customBrighter(factor: Float = 0.1f): Color =
    Color(
        (red + (255 - red) * factor).toInt().coerceIn(0, 255),
        (green + (255 - green) * factor).toInt().coerceIn(0, 255),
        (blue + (255 - blue) * factor).toInt().coerceIn(0, 255),
        alpha,
    )

fun Color.customDarker(factor: Float = 0.1f): Color =
    Color(
        (red * (1 - factor)).toInt().coerceIn(0, 255),
        (green * (1 - factor)).toInt().coerceIn(0, 255),
        (blue * (1 - factor)).toInt().coerceIn(0, 255),
        alpha,
    )

fun Color.withLightMode(): Color = if (JBColor.isBright()) brighter() else this

fun Color.contrastWithTheme(): Color = if (JBColor.isBright()) customDarker(0.15f) else customBrighter(0.15f)

fun relativePath(
    project: Project,
    virtualFile: VirtualFile?,
): String? = virtualFile?.let { relativePath(project, it.path) }

fun relativePath(
    project: Project,
    filePath: String?,
): String? {
    if (filePath.isNullOrBlank()) return null
    val basePath = project.basePath ?: return filePath
    return try {
        val base = Paths.get(basePath).toAbsolutePath().normalize()
        val path = Paths.get(filePath).toAbsolutePath().normalize()
        if (path.startsWith(base)) {
            base.relativize(path).toString().replace(File.separatorChar, '/')
        } else {
            filePath
        }
    } catch (_: Exception) {
        filePath
    }
}

fun readFile(
    project: Project,
    filePath: String,
    maxLines: Int = Int.MAX_VALUE,
): String? {
    return try {
        val file = File(filePath).takeIf { it.isAbsolute } ?: File(project.basePath ?: "", filePath)
        if (!file.isFile) {
            null
        } else {
            file.useLines { lines -> lines.take(maxLines).joinToString("\n") }
        }
    } catch (_: Exception) {
        null
    }
}

fun matchesExclusionPattern(
    fileName: String,
    pattern: String,
): Boolean {
    if (pattern.isBlank()) return false
    return if (pattern.endsWith("**")) {
        fileName.startsWith(pattern.removeSuffix("**"), ignoreCase = true)
    } else {
        fileName.endsWith(pattern, ignoreCase = true)
    }
}

fun userSpecificRepoName(project: Project): String = File(project.basePath ?: "unknown").name

fun isFileTooLarge(
    text: String,
    @Suppress("unused") project: Project,
): Boolean = text.length > 500_000 || text.lines().size > 20_000

fun convertPythonToKotlinIndex(
    text: String,
    pythonIndex: Int,
): Int {
    if (pythonIndex <= 0) return 0
    var codePointIndex = 0
    var charIndex = 0
    while (charIndex < text.length && codePointIndex < pythonIndex) {
        charIndex += Character.charCount(Character.codePointAt(text, charIndex))
        codePointIndex++
    }
    return charIndex.coerceIn(0, text.length)
}

fun getKeyStrokesForAction(actionId: String): List<KeyStroke> =
    ActionManager
        .getInstance()
        .getAction(actionId)
        ?.shortcutSet
        ?.shortcuts
        ?.mapNotNull(Shortcut::asKeyStroke)
        ?: emptyList()

private fun Shortcut.asKeyStroke(): KeyStroke? = (this as? KeyboardShortcut)?.firstKeyStroke

fun parseKeyStrokesToPrint(keyStroke: KeyStroke): String = StringUtil.capitalizeWords(KeyStroke.getKeyStroke(keyStroke.keyCode, keyStroke.modifiers).toString(), true)

fun getCurrentSweepPluginVersion(): String? = null

fun getDebugInfo(): String =
    try {
        val application = ApplicationInfo.getInstance()
        "${application.fullApplicationName} (${application.build})"
    } catch (_: Exception) {
        "Unknown IDE"
    }

object StringDistance {
    fun levenshteinDistance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = i
            costs[0] = i
            for (j in 1..b.length) {
                val current =
                    minOf(
                        costs[j] + 1,
                        previous + 1,
                        costs[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                costs[j - 1] = previous
                previous = current
            }
            costs[b.length] = previous
        }
        return costs[b.length]
    }
}

fun editorIndentString(editor: Editor): String = " ".repeat(editor.settings.getTabSize(editor.project))

class DocumentChangeListenerAdapter(
    private val listener: DocumentListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = listener(event)
}

class CaretPositionChangedAdapter(
    private val listener: CaretListener.(event: CaretEvent) -> Unit,
) : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) = listener(event)
}

fun getPublicIPAddress(): String? = null

fun getFirstWord(text: String): Pair<String, String>? {
    if (text.isEmpty()) return null

    // Define word delimiters - space, tab, newline, and common punctuation
    val wordDelimiters = setOf(' ', '\t', '\n', '.', '(', ')', '{', '}', '[', ']', ';', ',', ':', '!', '?')
    var endIndex = 0

    // Skip any leading whitespace
    while (endIndex < text.length && text[endIndex].isWhitespace()) {
        endIndex++
    }

    // Find the end of the next word
    while (endIndex < text.length && text[endIndex] !in wordDelimiters) {
        endIndex++
    }

    // Include the delimiter if it's a space (common case for natural text flow)
    if (endIndex < text.length && text[endIndex] == ' ') {
        endIndex++
    }

    // If no word was found (endIndex is 0), return the first character
    if (endIndex == 0) {
        val firstChar = text.substring(0, 1)
        val remainingContent = text.substring(1)
        return Pair(firstChar, remainingContent)
    }

    val firstWord = text.substring(0, endIndex)
    val remainingContent = text.substring(endIndex)

    return Pair(firstWord, remainingContent)
}

fun Char.requiresSpecialFontHandling(): Boolean =
    Character.UnicodeScript.of(code) in
        setOf(
            Character.UnicodeScript.ARABIC,
            Character.UnicodeScript.HEBREW,
            Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.BENGALI,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
        )

fun splitTextOnComplexScript(text: String): List<Pair<String, Boolean>> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<Pair<String, Boolean>>()
    val current = StringBuilder()
    var currentComplex = text.first().requiresSpecialFontHandling()
    for (char in text) {
        val complex = char.requiresSpecialFontHandling()
        if (complex != currentComplex && current.isNotEmpty()) {
            result.add(current.toString() to currentComplex)
            current.clear()
            currentComplex = complex
        }
        current.append(char)
    }
    if (current.isNotEmpty()) result.add(current.toString() to currentComplex)
    return result
}

fun FontMetrics.getStringWidthWithTabs(
    text: String,
    editor: Editor,
): Int {
    val tabWidth = charWidth(' ') * editor.settings.getTabSize(editor.project)
    var width = 0
    text.forEach { width += if (it == '\t') tabWidth else charWidth(it) }
    return width
}

fun Graphics.drawStringWithTabs(
    text: String,
    x: Int,
    y: Int,
    editor: Editor,
): Int {
    val metrics = fontMetrics
    var currentX = x
    val tabWidth = metrics.charWidth(' ') * editor.settings.getTabSize(editor.project)
    text.forEach { char ->
        if (char == '\t') {
            currentX += tabWidth
        } else {
            drawString(char.toString(), currentX, y)
            currentX += metrics.charWidth(char)
        }
    }
    return currentX - x
}

fun Color.withReducedSaturationPreservingLuminance(
    saturationFactor: Float,
    alphaFactor: Float = 1f,
): Color {
    val hsb = Color.RGBtoHSB(red, green, blue, null)
    val rgb = Color.getHSBColor(hsb[0], (hsb[1] * saturationFactor).coerceIn(0f, 1f), hsb[2])
    return Color(rgb.red, rgb.green, rgb.blue, (alpha * alphaFactor).toInt().coerceIn(0, 255))
}

fun Color.withAdjustedBrightnessPreservingHue(
    lightFactor: Float,
    darkFactor: Float = lightFactor,
): Color {
    val factor = if (JBColor.isBright()) lightFactor else darkFactor
    val hsb = Color.RGBtoHSB(red, green, blue, null)
    val rgb = Color.getHSBColor(hsb[0], hsb[1], (hsb[2] * factor).coerceIn(0f, 1f))
    return Color(rgb.red, rgb.green, rgb.blue, alpha)
}

fun PsiElement.linesRange(document: com.intellij.openapi.editor.Document): IntRange {
    val start = textRange.startOffset.coerceIn(0, document.textLength)
    val end = textRange.endOffset.coerceIn(start, document.textLength)
    return document.getLineNumber(start)..document.getLineNumber(end)
}

fun tryLoadClass(name: String): Class<*>? =
    try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

fun tryGetStaticMethod(
    clazz: Class<*>?,
    name: String,
    vararg parameterTypes: Class<*>,
): java.lang.reflect.Method? =
    try {
        clazz?.getMethod(name, *parameterTypes)
    } catch (_: Throwable) {
        null
    }

fun tryMethod(
    clazz: Class<*>?,
    name: String,
): java.lang.reflect.Method? =
    try {
        clazz?.methods?.firstOrNull { it.name == name }
    } catch (_: Throwable) {
        null
    }

fun tryMethodWithParams(
    clazz: Class<*>?,
    name: String,
    vararg parameterTypes: Class<*>,
): java.lang.reflect.Method? =
    try {
        clazz?.getMethod(name, *parameterTypes)
    } catch (_: Throwable) {
        null
    }

fun Any.tryInvokeMethod(
    name: String,
    vararg args: Any?,
): Any? =
    try {
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }?.invoke(this, *args)
    } catch (_: Throwable) {
        null
    }

fun tryInvokeMethod(
    target: Any?,
    method: java.lang.reflect.Method?,
    vararg args: Any?,
): Any? =
    try {
        if (target == null || method == null) null else method.invoke(target, *args)
    } catch (_: Throwable) {
        null
    }

fun Any.tryInvokeStaticMethod(
    name: String,
    vararg args: Any?,
): Any? =
    try {
        (this as? Class<*>)?.methods?.firstOrNull { it.name == name && it.parameterCount == args.size }?.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

fun tryInvokeStaticMethod(
    method: java.lang.reflect.Method?,
    vararg args: Any?,
): Any? =
    try {
        method?.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

fun disableFullLineCompletion(project: Project) = Unit
