package dev.sweep.assistant.utils

import com.github.difflib.DiffUtils
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

fun getDiff(
    oldContent: String,
    newContent: String,
    oldFileName: String = "oldFile",
    newFileName: String = "newFile",
    context: Int = 3,
    cleanEndings: Boolean = false,
): String {
    val normalizedOldContent = oldContent.replace("\r\n", "\n")
    val normalizedNewContent = newContent.replace("\r\n", "\n")
    return ByteArrayOutputStream()
        .apply {
            val oldText =
                RawText(
                    (if (cleanEndings) normalizedOldContent.trimEnd() + "\n" else normalizedOldContent)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            val newText =
                RawText(
                    (if (cleanEndings) normalizedNewContent.trimEnd() + "\n" else normalizedNewContent)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            val comparator = RawTextComparator.DEFAULT
            val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS).diff(comparator, oldText, newText)
            DiffFormatter(this).apply {
                setContext(context)
                setDiffComparator(comparator)
                write("--- $oldFileName\n".toByteArray())
                write("+++ $newFileName\n".toByteArray())
                format(edits, oldText, newText)
                flush()
            }
        }.toString(StandardCharsets.UTF_8)
        .replace("\\ No newline at end of file\n", "")
}

data class DiffGroup(
    val deletions: String,
    val additions: String,
    val index: Int,
) {
    val hasAdditions get() = additions.isNotEmpty()
    val hasDeletions get() = deletions.isNotEmpty()
}

val List<DiffGroup>.isAllAdditions get() = none { it.hasDeletions }
val List<DiffGroup>.isAllDeletions get() = none { it.hasAdditions }

fun computeCharacterDiff(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    if (newContent.startsWith(oldContent)) {
        val addition = newContent.removePrefix(oldContent)
        if (addition.isNotEmpty()) return listOf(DiffGroup("", addition, oldContent.length))
    }

    val patch = DiffUtils.diff(oldContent.toMutableList(), newContent.toMutableList())
    return patch.deltas.map { delta ->
        DiffGroup(
            deletions = delta.source.lines.joinToString(""),
            additions = delta.target.lines.joinToString(""),
            index = delta.source.position,
        )
    }
}

fun computeWordDiff(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    val oldWords = oldContent.split(Regex("(?<=[^\\w\n])|(?=[^\\w\n])|(?<=\n)|(?=\n)")).filter { it.isNotEmpty() }
    val newWords = newContent.split(Regex("(?<=[^\\w\n])|(?=[^\\w\n])|(?<=\n)|(?=\n)")).filter { it.isNotEmpty() }
    val patch = DiffUtils.diff(oldWords, newWords)
    return patch.deltas.sortedBy { it.source.position }.map { delta ->
        val position = oldWords.take(delta.source.position).joinToString("").length
        DiffGroup(
            deletions = delta.source.lines.joinToString(""),
            additions = delta.target.lines.joinToString(""),
            index = position,
        )
    }
}

fun computeDiffGroups(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    if (newContent.isEmpty() && oldContent.isNotEmpty()) {
        return listOf(DiffGroup(oldContent, "", 0))
    }

    val oldLines = oldContent.lines()
    val newLines = newContent.lines()
    val linePatch = DiffUtils.diff(oldLines, newLines)
    val groups = mutableListOf<DiffGroup>()

    fun joinLines(lines: List<String>): String = lines.joinToString("\n") + if (lines.size == 1 && lines.first().isEmpty()) "\n" else ""

    linePatch.deltas.forEach { delta ->
        val oldText = joinLines(delta.source.lines)
        val newText = joinLines(delta.target.lines)
        val position = oldLines.take(delta.source.position).joinToString("\n").length + if (delta.source.position > 0) 1 else 0

        when {
            oldText.isEmpty() -> groups.add(DiffGroup("", newText + "\n", position))
            newText.isEmpty() -> groups.add(DiffGroup(oldText + "\n", "", position))
            else -> {
                val inner = if (delta.source.lines.size <= 1 && delta.target.lines.size <= 1) computeCharacterDiff(oldText, newText) else computeWordDiff(oldText, newText)
                inner.forEach { groups.add(it.copy(index = position + it.index)) }
            }
        }
    }

    return groups.sortedBy { it.index }
}
