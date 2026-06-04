package com.oroncal.semaphoreui.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.oroncal.semaphoreui.settings.SemaphoreAuthMode
import com.oroncal.semaphoreui.settings.SemaphoreConnectionProfile
import com.oroncal.semaphoreui.settings.SemaphoreProxyMode
import java.io.Closeable
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SemaphoreApiClient(
    private val profile: SemaphoreConnectionProfile,
    private val mapper: ObjectMapper = defaultObjectMapper(),
) {
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    @Volatile
    private var sessionAuthenticated = false

    private val webSocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(proxyForProfile())
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun testConnection(): SemaphoreConnectionStatus {
        val pong = rawRequest("/ping", authenticated = false).trim()
        val info: SemaphoreInfo = jsonRequest("/info")
        return SemaphoreConnectionStatus(ping = pong, info = info)
    }

    fun fetchProjects(): List<SemaphoreProject> = jsonRequest("/projects")

    fun fetchTemplates(projectId: Int): List<SemaphoreTemplate> {
        return jsonRequest("/project/$projectId/templates?sort=name&order=asc")
    }

    fun fetchViews(projectId: Int): List<SemaphoreView> {
        return jsonRequest("/project/$projectId/views")
    }

    fun fetchLatestTemplateTask(projectId: Int, templateId: Int): SemaphoreTask? {
        return jsonRequest<List<SemaphoreTask>>("/project/$projectId/templates/$templateId/tasks/last?limit=1").firstOrNull()
    }

    fun fetchRecentTasks(projectId: Int): List<SemaphoreTask> {
        return jsonRequest("/project/$projectId/tasks/last")
    }

    fun fetchTaskOutput(projectId: Int, taskId: Int): List<SemaphoreTaskOutput> {
        return jsonRequest("/project/$projectId/tasks/$taskId/output")
    }

    fun fetchTaskRawOutput(projectId: Int, taskId: Int): String {
        return rawRequest("/project/$projectId/tasks/$taskId/raw_output")
    }

    fun runTask(projectId: Int, request: SemaphoreRunTaskRequest): SemaphoreTask {
        return jsonRequest("/project/$projectId/tasks", method = "POST", body = request)
    }

    fun stopTask(projectId: Int, taskId: Int, force: Boolean = false) {
        val body = if (force) mapOf("force" to true) else null
        rawRequest("/project/$projectId/tasks/$taskId/stop", method = "POST", body = body)
    }

    fun openRealtime(listener: SemaphoreRealtimeListener): SemaphoreRealtimeConnection {
        val suppression = acquireSocksAuthenticationSuppression()
        val released = AtomicBoolean(false)
        fun releaseSuppression() {
            if (released.compareAndSet(false, true)) {
                suppression.close()
            }
        }

        try {
            if (profile.authMode == SemaphoreAuthMode.USERNAME_PASSWORD && !sessionAuthenticated) {
                login()
            }

            val uri = URI.create(resolveWebSocketUrl())
            val request = Request.Builder()
                .url(uri.toString())
                .apply { addCookieHeaders(uri) }
                .apply {
                    if (profile.authMode == SemaphoreAuthMode.API_TOKEN) {
                        header("Authorization", "Bearer ${profile.apiToken.orEmpty()}")
                    }
                }
                .build()

            val socket = webSocketClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        listener.onOpen()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        listener.onMessage(mapper.readValue(text))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        releaseSuppression()
                        listener.onClosed()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        releaseSuppression()
                        listener.onFailure(t)
                    }
                },
            )

            return SemaphoreRealtimeConnection {
                if (!socket.close(NORMAL_CLOSURE_STATUS, "Semaphore UI tool window closed")) {
                    socket.cancel()
                }
                releaseSuppression()
            }
        } catch (throwable: Throwable) {
            releaseSuppression()
            throw throwable
        }
    }

    private inline fun <reified T> jsonRequest(
        path: String,
        method: String = "GET",
        body: Any? = null,
    ): T {
        return mapper.readValue(rawRequest(path = path, method = method, body = body))
    }

    private fun rawRequest(
        path: String,
        method: String = "GET",
        body: Any? = null,
        authenticated: Boolean = true,
        retryAfterLogin: Boolean = true,
    ): String {
        return withSocksAuthenticationPromptSuppressed {
            if (authenticated && profile.authMode == SemaphoreAuthMode.USERNAME_PASSWORD && !sessionAuthenticated) {
                login()
            }

            val uri = URI.create(resolveUrl(path))
            val connection = openConnection(uri, method, body, authenticated)
            try {
                val statusCode = connection.responseCode
                val responseBody = readResponseBody(connection)
                storeCookies(uri, connection)

                if (statusCode == 401 && authenticated && retryAfterLogin && profile.authMode == SemaphoreAuthMode.USERNAME_PASSWORD) {
                    sessionAuthenticated = false
                    login()
                    return@withSocksAuthenticationPromptSuppressed rawRequest(path, method, body, authenticated = true, retryAfterLogin = false)
                }

                if (statusCode !in 200..299) {
                    throw SemaphoreApiException(
                        message = "Semaphore API returned $statusCode for $method $path",
                        statusCode = statusCode,
                        responseBody = responseBody,
                    )
                }

                responseBody
            } finally {
                connection.disconnect()
            }
        }
    }

    @Synchronized
    private fun login() {
        withSocksAuthenticationPromptSuppressed {
            if (sessionAuthenticated) {
                return@withSocksAuthenticationPromptSuppressed
            }

            val username = profile.username?.takeIf { it.isNotBlank() }
                ?: throw SemaphoreConfigurationException("The Semaphore username is not configured.")
            val password = profile.password?.takeIf { it.isNotBlank() }
                ?: throw SemaphoreConfigurationException("The Semaphore password is not configured.")

            val uri = URI.create(resolveUrl("/auth/login"))
            val payload = mapper.writeValueAsString(LoginRequest(username, password))
            val connection = openConnection(uri, method = "POST", body = payload, authenticated = false)
            try {
                val statusCode = connection.responseCode
                val responseBody = readResponseBody(connection)
                storeCookies(uri, connection)
                if (statusCode !in 200..299) {
                    throw SemaphoreApiException(
                        message = "Unable to log in to Semaphore ($statusCode).",
                        statusCode = statusCode,
                        responseBody = responseBody,
                    )
                }
                sessionAuthenticated = true
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(
        uri: URI,
        method: String,
        body: Any?,
        authenticated: Boolean,
    ): HttpURLConnection {
        val connection = uri.toURL().openConnection(proxyForProfile()) as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")

        addCookies(uri, connection)

        if (authenticated && profile.authMode == SemaphoreAuthMode.API_TOKEN) {
            connection.setRequestProperty("Authorization", "Bearer ${profile.apiToken.orEmpty()}")
        }

        when (body) {
            null -> Unit
            is String -> writeRequestBody(connection, body)
            else -> writeRequestBody(connection, mapper.writeValueAsString(body))
        }

        return connection
    }

    private fun Request.Builder.addCookieHeaders(uri: URI): Request.Builder {
        cookieManager.get(uri, emptyMap()).forEach { (headerName, headerValues) ->
            if (headerName != null && headerValues.isNotEmpty()) {
                header(headerName, headerValues.joinToString("; "))
            }
        }
        return this
    }

    private fun addCookies(uri: URI, connection: HttpURLConnection) {
        val cookieHeaders = cookieManager.get(uri, emptyMap())
        cookieHeaders.forEach { (headerName, headerValues) ->
            if (headerName != null && headerValues.isNotEmpty()) {
                connection.setRequestProperty(headerName, headerValues.joinToString("; "))
            }
        }
    }

    private fun storeCookies(uri: URI, connection: HttpURLConnection) {
        val responseHeaders = connection.headerFields
            .entries
            .filter { it.key != null }
            .associate { it.key!! to it.value }
        cookieManager.put(uri, responseHeaders)
    }

    private fun writeRequestBody(connection: HttpURLConnection, body: String) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { output ->
            output.write(payload)
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream: InputStream = connection.inputStreamOrError() ?: return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun HttpURLConnection.inputStreamOrError(): InputStream? {
        return try {
            inputStream
        } catch (_: Exception) {
            errorStream
        }
    }

    private fun proxyForProfile(): Proxy {
        val proxy = profile.proxy
        return when (proxy.mode) {
            SemaphoreProxyMode.DIRECT -> Proxy.NO_PROXY
            SemaphoreProxyMode.HTTP -> Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port ?: DEFAULT_PROXY_PORT))
            SemaphoreProxyMode.SOCKS5 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port ?: DEFAULT_PROXY_PORT))
        }
    }

    private fun <T> withSocksAuthenticationPromptSuppressed(action: () -> T): T {
        if (profile.proxy.mode != SemaphoreProxyMode.SOCKS5) {
            return action()
        }

        acquireSocksAuthenticationSuppression().use {
            return action()
        }
    }

    private fun acquireSocksAuthenticationSuppression(): Closeable {
        if (profile.proxy.mode != SemaphoreProxyMode.SOCKS5) {
            return Closeable {}
        }

        return acquireSocksAuthenticationSuppression(
            host = profile.proxy.host,
            port = profile.proxy.port ?: DEFAULT_PROXY_PORT,
        )
    }

    private fun resolveUrl(path: String): String {
        val apiBase = apiBaseUrl()
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return apiBase + normalizedPath
    }

    private fun resolveWebSocketUrl(): String {
        return apiBaseUrl()
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws"
    }

    private fun apiBaseUrl(): String {
        val normalizedServer = profile.serverUrl.trim().removeSuffix("/")
        if (normalizedServer.isBlank()) {
            throw SemaphoreConfigurationException("The Semaphore URL is not configured.")
        }

        return if (normalizedServer.endsWith("/api")) normalizedServer else "$normalizedServer/api"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val NORMAL_CLOSURE_STATUS = 1000
        private const val DEFAULT_PROXY_PORT = 1080
        private val SOCKS_AUTH_SUPPRESSION_LOCK = Any()
        private val ACTIVE_SOCKS_PROXY_ENDPOINTS = mutableMapOf<SocksProxyEndpoint, Int>()

        private fun acquireSocksAuthenticationSuppression(host: String, port: Int): Closeable {
            val endpoint = SocksProxyEndpoint(host.trim().lowercase(), port)
            synchronized(SOCKS_AUTH_SUPPRESSION_LOCK) {
                val currentAuthenticator = Authenticator.getDefault()
                if (currentAuthenticator !is NonInteractiveSocksAuthenticator) {
                    Authenticator.setDefault(NonInteractiveSocksAuthenticator(currentAuthenticator))
                }
                ACTIVE_SOCKS_PROXY_ENDPOINTS[endpoint] = (ACTIVE_SOCKS_PROXY_ENDPOINTS[endpoint] ?: 0) + 1
            }

            return Closeable {
                synchronized(SOCKS_AUTH_SUPPRESSION_LOCK) {
                    val remaining = (ACTIVE_SOCKS_PROXY_ENDPOINTS[endpoint] ?: 1) - 1
                    if (remaining > 0) {
                        ACTIVE_SOCKS_PROXY_ENDPOINTS[endpoint] = remaining
                    } else {
                        ACTIVE_SOCKS_PROXY_ENDPOINTS.remove(endpoint)
                    }

                    val currentAuthenticator = Authenticator.getDefault()
                    if (ACTIVE_SOCKS_PROXY_ENDPOINTS.isEmpty() && currentAuthenticator is NonInteractiveSocksAuthenticator) {
                        Authenticator.setDefault(currentAuthenticator.restoreDelegate())
                    }
                }
            }
        }

        private fun shouldSuppressSocksAuthentication(
            requestingHost: String?,
            requestingPort: Int,
            requestingProtocol: String?,
            requestingPrompt: String?,
        ): Boolean {
            val isSocksAuthenticationRequest =
                requestingProtocol.equals("SOCKS5", ignoreCase = true) ||
                    requestingPrompt.equals("SOCKS authentication", ignoreCase = true)
            if (!isSocksAuthenticationRequest) {
                return false
            }

            synchronized(SOCKS_AUTH_SUPPRESSION_LOCK) {
                if (ACTIVE_SOCKS_PROXY_ENDPOINTS.isEmpty()) {
                    return false
                }

                val normalizedHost = requestingHost?.trim()?.lowercase().orEmpty()
                if (normalizedHost.isBlank() || requestingPort <= 0) {
                    return true
                }

                return ACTIVE_SOCKS_PROXY_ENDPOINTS.keys.any { endpoint ->
                    endpoint.host == normalizedHost && endpoint.port == requestingPort
                }
            }
        }

        fun defaultObjectMapper(): ObjectMapper {
            return jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private data class LoginRequest(
        val auth: String,
        val password: String,
    )

    private data class SocksProxyEndpoint(
        val host: String,
        val port: Int,
    )

    private class NonInteractiveSocksAuthenticator(
        private val delegate: Authenticator?,
    ) : Authenticator() {
        fun restoreDelegate(): Authenticator? = delegate

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (shouldSuppressSocksAuthentication(requestingHost, requestingPort, requestingProtocol, requestingPrompt)) {
                return null
            }

            return delegate?.requestPasswordAuthenticationInstance(
                requestingHost,
                requestingSite,
                requestingPort,
                requestingProtocol,
                requestingPrompt,
                requestingScheme,
                requestingURL,
                requestorType,
            )
        }
    }
}

class SemaphoreApiException(
    override val message: String,
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException(message)

class SemaphoreConfigurationException(
    override val message: String,
) : RuntimeException(message)
