package dev.sweet.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "SweetMetaData", storages = [Storage("SweetMetaData.xml")])
class SweetMetaData : PersistentStateComponent<SweetMetaData.MetaData> {
    data class MetaData(
        var autocompleteAcceptCount: Int = 0,
        var hasUsedLookupItem: Boolean = false,
    )

    private var metaData = MetaData()

    override fun getState(): MetaData = metaData

    override fun loadState(state: MetaData) {
        metaData = state
    }

    var autocompleteAcceptCount: Int
        get() = metaData.autocompleteAcceptCount
        set(value) {
            metaData.autocompleteAcceptCount = value
        }

    var hasUsedLookupItem: Boolean
        get() = metaData.hasUsedLookupItem
        set(value) {
            metaData.hasUsedLookupItem = value
        }

    companion object {
        fun getInstance(): SweetMetaData = ApplicationManager.getApplication().getService(SweetMetaData::class.java)
    }
}
