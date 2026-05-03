package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SweepProjectService : Disposable {
    companion object {
        fun getInstance(project: Project): SweepProjectService = project.getService(SweepProjectService::class.java)
    }

    override fun dispose() = Unit
}
