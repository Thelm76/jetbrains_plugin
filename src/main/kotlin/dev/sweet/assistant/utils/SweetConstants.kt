package dev.sweet.assistant.utils

import com.intellij.openapi.extensions.PluginId

object SweetConstants {
    enum class GatewayMode {
        CLIENT,
        HOST,
        NA,
    }

    const val PLUGIN_NAME = "Sweet Autocomplete"
    const val COMMON_SYMBOLS_REGEX = """[()\[\]{}<>,.;:=+\-*/%!&|^~?'"`]"""
    val GATEWAY_MODE: GatewayMode = GatewayMode.NA
    val PLUGINS_TO_DISABLE: List<PluginId> =
        listOf(
            PluginId.getId("org.jetbrains.completion.full.line"),
            PluginId.getId("com.github.copilot"),
        )
    val PLUGIN_ID_TO_NAME: Map<PluginId, String> =
        mapOf(
            PluginId.getId("org.jetbrains.completion.full.line") to "Full Line Code Completion",
            PluginId.getId("com.github.copilot") to "GitHub Copilot",
        )

    val EXTENSION_TO_LANGUAGE =
        mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "py" to "python",
            "js" to "javascript",
            "jsx" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "go" to "go",
            "rs" to "rust",
            "rb" to "ruby",
            "cpp" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "h" to "cpp",
            "hpp" to "cpp",
            "cs" to "csharp",
        )

    val LANGUAGE_KEYWORDS =
        mapOf(
            "kotlin" to setOf("fun", "val", "var", "class", "object", "if", "else", "when", "return", "null", "true", "false"),
            "java" to setOf("class", "interface", "public", "private", "protected", "static", "final", "return", "null", "true", "false"),
            "python" to setOf("def", "class", "if", "else", "elif", "return", "None", "True", "False", "import", "from"),
            "javascript" to setOf("function", "const", "let", "var", "class", "if", "else", "return", "null", "true", "false"),
            "typescript" to setOf("function", "const", "let", "var", "class", "interface", "type", "if", "else", "return", "null", "true", "false"),
            "go" to setOf("func", "type", "struct", "interface", "if", "else", "return", "nil", "true", "false"),
            "rust" to setOf("fn", "let", "mut", "struct", "enum", "impl", "if", "else", "return", "true", "false"),
            "ruby" to setOf("def", "class", "module", "if", "else", "elsif", "return", "nil", "true", "false"),
            "cpp" to setOf("class", "struct", "if", "else", "return", "nullptr", "true", "false", "const", "auto"),
            "csharp" to setOf("class", "interface", "public", "private", "protected", "static", "return", "null", "true", "false"),
        )
}
