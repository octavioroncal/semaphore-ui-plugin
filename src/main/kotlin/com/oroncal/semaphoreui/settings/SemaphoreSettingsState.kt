package com.oroncal.semaphoreui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "SemaphoreUiSettings", storages = [Storage("semaphoreui.xml")])
class SemaphoreSettingsState : PersistentStateComponent<SemaphoreSettingsState.State> {
    data class State(
        var serverUrl: String = "",
        var authMode: String = SemaphoreAuthMode.API_TOKEN.name,
        var username: String = "",
        var proxyMode: String = SemaphoreProxyMode.DIRECT.name,
        var proxyHost: String = "",
        var proxyPort: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getConnectionProfile(): SemaphoreConnectionProfile {
        val credentialStore = service<SemaphoreCredentialStore>()
        return SemaphoreConnectionProfile(
            serverUrl = state.serverUrl.trim(),
            authMode = SemaphoreAuthMode.fromStorage(state.authMode),
            apiToken = credentialStore.getApiToken(),
            username = state.username.trim().ifBlank { null },
            password = credentialStore.getPassword(),
            proxy = SemaphoreProxySettings(
                mode = SemaphoreProxyMode.fromStorage(state.proxyMode),
                host = state.proxyHost.trim(),
                port = state.proxyPort.trim().toIntOrNull(),
            ),
        )
    }

    fun update(
        serverUrl: String,
        authMode: SemaphoreAuthMode,
        username: String,
        proxyMode: SemaphoreProxyMode,
        proxyHost: String,
        proxyPort: String,
    ) {
        state.serverUrl = serverUrl.trim()
        state.authMode = authMode.name
        state.username = username.trim()
        state.proxyMode = proxyMode.name
        state.proxyHost = proxyHost.trim()
        state.proxyPort = proxyPort.trim()
    }
}
