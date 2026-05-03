package dev.sweet.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SweetProjectService : Disposable {
    companion object {
        fun getInstance(project: Project): SweetProjectService = project.getService(SweetProjectService::class.java)
    }

    override fun dispose() = Unit
}
