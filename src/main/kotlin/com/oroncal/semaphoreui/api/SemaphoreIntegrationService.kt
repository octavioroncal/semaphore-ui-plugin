package com.oroncal.semaphoreui.api

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.oroncal.semaphoreui.settings.SemaphoreAuthMode
import com.oroncal.semaphoreui.settings.SemaphoreConnectionProfile
import com.oroncal.semaphoreui.settings.SemaphoreProxyMode
import com.oroncal.semaphoreui.settings.SemaphoreSettingsState

@Service(Service.Level.APP)
class SemaphoreIntegrationService {
    @Volatile
    private var cachedProfile: SemaphoreConnectionProfile? = null

    @Volatile
    private var cachedClient: SemaphoreApiClient? = null

    fun testConnection(): SemaphoreConnectionStatus = client().testConnection()

    fun fetchProjects(): List<SemaphoreProject> = client().fetchProjects()

    fun fetchTemplates(projectId: Int): List<SemaphoreTemplate> = client().fetchTemplates(projectId)

    fun fetchViews(projectId: Int): List<SemaphoreView> = client().fetchViews(projectId)

    fun fetchLatestTemplateTask(projectId: Int, templateId: Int): SemaphoreTask? = client().fetchLatestTemplateTask(projectId, templateId)

    fun fetchRecentTasks(projectId: Int): List<SemaphoreTask> = client().fetchRecentTasks(projectId)

    fun fetchTaskOutput(projectId: Int, taskId: Int): List<SemaphoreTaskOutput> = client().fetchTaskOutput(projectId, taskId)

    fun fetchTaskRawOutput(projectId: Int, taskId: Int): String = client().fetchTaskRawOutput(projectId, taskId)

    fun runTask(projectId: Int, request: SemaphoreRunTaskRequest): SemaphoreTask = client().runTask(projectId, request)

    fun stopTask(projectId: Int, taskId: Int, force: Boolean = false) = client().stopTask(projectId, taskId, force)

    fun openRealtime(listener: SemaphoreRealtimeListener): SemaphoreRealtimeConnection = client().openRealtime(listener)

    fun invalidateClient() {
        cachedProfile = null
        cachedClient = null
    }

    @Synchronized
    private fun client(): SemaphoreApiClient {
        val settings = service<SemaphoreSettingsState>()
        val profile = settings.getConnectionProfile().validate()
        val currentClient = cachedClient
        if (currentClient != null && cachedProfile == profile) {
            return currentClient
        }

        return SemaphoreApiClient(profile).also {
            cachedProfile = profile
            cachedClient = it
        }
    }

    private fun SemaphoreConnectionProfile.validate(): SemaphoreConnectionProfile {
        if (serverUrl.isBlank()) {
            throw SemaphoreConfigurationException("Configure the Semaphore URL in Settings.")
        }

        when (authMode) {
            SemaphoreAuthMode.API_TOKEN -> {
                if (apiToken.isNullOrBlank()) {
                    throw SemaphoreConfigurationException("Configure a Semaphore API token in Settings.")
                }
            }

            SemaphoreAuthMode.USERNAME_PASSWORD -> {
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    throw SemaphoreConfigurationException("Configure the Semaphore username and password in Settings.")
                }
            }
        }

        if (proxy.mode != SemaphoreProxyMode.DIRECT) {
            if (proxy.host.isBlank()) {
                throw SemaphoreConfigurationException("Configure the proxy host in Settings.")
            }
            val proxyPort = proxy.port
                ?: throw SemaphoreConfigurationException("Configure a valid proxy port in Settings.")
            if (proxyPort !in 1..65535) {
                throw SemaphoreConfigurationException("The proxy port must be between 1 and 65535.")
            }
        }

        return this
    }
}
