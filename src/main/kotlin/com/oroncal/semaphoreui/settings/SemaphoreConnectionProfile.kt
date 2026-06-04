package com.oroncal.semaphoreui.settings

enum class SemaphoreAuthMode(val label: String) {
    API_TOKEN("API Token"),
    USERNAME_PASSWORD("Username and Password");

    override fun toString(): String = label

    companion object {
        fun fromStorage(value: String?): SemaphoreAuthMode {
            return entries.firstOrNull { it.name == value } ?: API_TOKEN
        }
    }
}

enum class SemaphoreProxyMode(val label: String) {
    DIRECT("No Proxy"),
    HTTP("HTTP"),
    SOCKS5("SOCKS5");

    override fun toString(): String = label

    companion object {
        fun fromStorage(value: String?): SemaphoreProxyMode {
            return entries.firstOrNull { it.name == value } ?: DIRECT
        }
    }
}

data class SemaphoreProxySettings(
    val mode: SemaphoreProxyMode,
    val host: String,
    val port: Int?,
)

data class SemaphoreConnectionProfile(
    val serverUrl: String,
    val authMode: SemaphoreAuthMode,
    val apiToken: String?,
    val username: String?,
    val password: String?,
    val proxy: SemaphoreProxySettings,
)
