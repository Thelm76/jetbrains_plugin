package dev.sweet.assistant.settings

data class ModelInfo(
    val id: String,
    val name: String = "",
    val description: String = "",
    val promptPrice: String = "",
    val completionPrice: String = "",
    val inputCacheReadPrice: String = "",
    val knowledgeCutoff: String = "",
)
