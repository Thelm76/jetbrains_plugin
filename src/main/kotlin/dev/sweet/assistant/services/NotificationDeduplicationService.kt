package dev.sweet.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class NotificationDeduplicationService(
    private val project: Project,
) {
    companion object {
        fun getInstance(project: Project): NotificationDeduplicationService = project.getService(NotificationDeduplicationService::class.java)
    }

    fun showNotificationWithDeduplicationAndErrorReporting(
        title: String,
        content: String,
        notificationGroup: String,
        type: NotificationType,
        exception: Throwable? = null,
        errorContext: String? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(notificationGroup)
                ?.createNotification(title, content, type)
                ?.notify(project)
        }
    }
}
