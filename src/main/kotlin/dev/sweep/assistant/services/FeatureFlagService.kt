package dev.sweep.assistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class FeatureFlagService(
    @Suppress("unused") private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)
    }

    fun isFeatureEnabled(flagName: String): Boolean = false

    fun getNumericFeatureFlag(
        flagName: String,
        defaultValue: Int,
    ): Int = defaultValue

    fun getStringFeatureFlag(
        flagName: String,
        defaultValue: String,
    ): String = defaultValue
}
