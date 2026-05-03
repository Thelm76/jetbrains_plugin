package dev.sweet.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class AutocompleteRequestStatusService : Disposable {
    companion object {
        fun getInstance(project: Project): AutocompleteRequestStatusService =
            project.getService(AutocompleteRequestStatusService::class.java)
    }

    private val activeRequestCount = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isRequestInProgress: Boolean
        get() = activeRequestCount.get() > 0

    fun requestStarted() {
        if (activeRequestCount.getAndIncrement() == 0) {
            notifyListeners()
        }
    }

    fun requestFinished() {
        while (true) {
            val current = activeRequestCount.get()
            if (current <= 0) return

            val next = current - 1
            if (activeRequestCount.compareAndSet(current, next)) {
                if (next == 0) {
                    notifyListeners()
                }
                return
            }
        }
    }

    fun addRequestStateListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeRequestStateListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    override fun dispose() {
        listeners.clear()
        activeRequestCount.set(0)
    }
}
