package com.oroncal.semaphoreui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SeparatorFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.oroncal.semaphoreui.api.SemaphoreIntegrationService
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class SemaphoreSettingsConfigurable : SearchableConfigurable {
    private val settingsState = service<SemaphoreSettingsState>()
    private val credentialStore = service<SemaphoreCredentialStore>()
    private val integrationService = service<SemaphoreIntegrationService>()

    private val serverUrlField = JBTextField()
    private val proxyModeCombo = ComboBox(SemaphoreProxyMode.entries.toTypedArray())
    private val proxyHostField = JBTextField()
    private val proxyPortField = JBTextField()
    private val authModeCombo = ComboBox(SemaphoreAuthMode.entries.toTypedArray())
    private val apiTokenField = JPasswordField()
    private val usernameField = JBTextField()
    private val passwordField = JPasswordField()
    private val proxyHintLabel = JBLabel(
        "<html>Direct, HTTP, and SOCKS5 proxies are supported.<br>SOCKS5 is always treated as unauthenticated. No interactive proxy credential dialogs are used.</html>",
    )
    private val authHintLabel = JBLabel()
    private val secretsStatusLabel = JBLabel("Loading stored credentials...")

    private val proxyCardLayout = CardLayout()
    private val proxyCardPanel = JPanel(proxyCardLayout)
    private val authCardLayout = CardLayout()
    private val authCardPanel = JPanel(authCardLayout)

    private val mainPanel: JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout())

    private var loadedApiToken: String = ""
    private var loadedPassword: String = ""
    private var secretsLoaded = false

    init {
        proxyCardPanel.add(JPanel(), ProxyCard.NONE.name)
        proxyCardPanel.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Proxy host:", proxyHostField)
                .addLabeledComponent("Proxy port:", proxyPortField)
                .panel,
            ProxyCard.CONFIGURED.name,
        )

        authCardPanel.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("API token:", apiTokenField)
                .panel,
            SemaphoreAuthMode.API_TOKEN.name,
        )
        authCardPanel.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Username:", usernameField)
                .addLabeledComponent("Password:", passwordField)
                .panel,
            SemaphoreAuthMode.USERNAME_PASSWORD.name,
        )

        val content = FormBuilder.createFormBuilder()
            .addLabeledComponent("Semaphore URL:", serverUrlField)
            .addLabeledComponent("Proxy type:", proxyModeCombo)
            .addComponent(proxyCardPanel)
            .addComponent(proxyHintLabel)
            .addComponent(SeparatorFactory.createSeparator("Authentication", null))
            .addLabeledComponent("Mode:", authModeCombo)
            .addComponent(authCardPanel)
            .addComponent(authHintLabel)
            .addComponent(secretsStatusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        mainPanel.border = JBUI.Borders.empty(12)
        mainPanel.add(content, BorderLayout.NORTH)

        proxyModeCombo.addActionListener { updateProxyState() }
        authModeCombo.addActionListener { updateAuthState() }
        proxyHintLabel.border = JBUI.Borders.emptyTop(8)
        authHintLabel.border = JBUI.Borders.emptyTop(8)
        secretsStatusLabel.border = JBUI.Borders.emptyTop(8)
    }

    override fun getId(): String = "com.oroncal.semaphoreui.settings"

    override fun getDisplayName(): String = "Semaphore UI"

    override fun createComponent(): JComponent {
        reset()
        loadSecretsAsync()
        return mainPanel
    }

    override fun isModified(): Boolean {
        val state = settingsState.state
        return state.serverUrl != serverUrlField.text.trim() ||
            SemaphoreProxyMode.fromStorage(state.proxyMode) != selectedProxyMode() ||
            state.proxyHost != proxyHostField.text.trim() ||
            state.proxyPort != proxyPortField.text.trim() ||
            SemaphoreAuthMode.fromStorage(state.authMode) != selectedAuthMode() ||
            state.username != usernameField.text.trim() ||
            (secretsLoaded && String(apiTokenField.password).trim() != loadedApiToken) ||
            (secretsLoaded && String(passwordField.password) != loadedPassword)
    }

    override fun apply() {
        settingsState.update(
            serverUrl = serverUrlField.text,
            authMode = selectedAuthMode(),
            username = usernameField.text,
            proxyMode = selectedProxyMode(),
            proxyHost = proxyHostField.text,
            proxyPort = proxyPortField.text,
        )

        if (secretsLoaded) {
            credentialStore.setApiToken(String(apiTokenField.password).trim())
            credentialStore.setPassword(String(passwordField.password))
            loadedApiToken = String(apiTokenField.password).trim()
            loadedPassword = String(passwordField.password)
        }

        integrationService.invalidateClient()
        updateAuthState()
    }

    override fun reset() {
        val state = settingsState.state
        serverUrlField.text = state.serverUrl
        proxyModeCombo.selectedItem = SemaphoreProxyMode.fromStorage(state.proxyMode)
        proxyHostField.text = state.proxyHost
        proxyPortField.text = state.proxyPort
        authModeCombo.selectedItem = SemaphoreAuthMode.fromStorage(state.authMode)
        usernameField.text = state.username
        apiTokenField.text = loadedApiToken
        passwordField.text = loadedPassword
        updateProxyState()
        updateAuthState()
    }

    private fun selectedAuthMode(): SemaphoreAuthMode {
        return authModeCombo.selectedItem as? SemaphoreAuthMode ?: SemaphoreAuthMode.API_TOKEN
    }

    private fun selectedProxyMode(): SemaphoreProxyMode {
        return proxyModeCombo.selectedItem as? SemaphoreProxyMode ?: SemaphoreProxyMode.DIRECT
    }

    private fun updateProxyState() {
        val proxyEnabled = selectedProxyMode() != SemaphoreProxyMode.DIRECT
        proxyCardLayout.show(proxyCardPanel, if (proxyEnabled) ProxyCard.CONFIGURED.name else ProxyCard.NONE.name)
        proxyHintLabel.isVisible = proxyEnabled
    }

    private fun updateAuthState() {
        val authMode = selectedAuthMode()
        authCardLayout.show(authCardPanel, authMode.name)
        apiTokenField.isEnabled = secretsLoaded && authMode == SemaphoreAuthMode.API_TOKEN
        usernameField.isEnabled = authMode == SemaphoreAuthMode.USERNAME_PASSWORD
        passwordField.isEnabled = secretsLoaded && authMode == SemaphoreAuthMode.USERNAME_PASSWORD
        authHintLabel.text = if (authMode == SemaphoreAuthMode.API_TOKEN) {
            "<html>Use a Bearer token to avoid session cookie handling.</html>"
        } else {
            "<html>Use this mode only when API tokens are not available.</html>"
        }
        secretsStatusLabel.isVisible = !secretsLoaded
    }

    private fun loadSecretsAsync() {
        secretsLoaded = false
        secretsStatusLabel.text = "Loading stored credentials..."
        updateAuthState()

        ApplicationManager.getApplication().executeOnPooledThread {
            val apiToken = credentialStore.getApiToken().orEmpty()
            val password = credentialStore.getPassword().orEmpty()

            ApplicationManager.getApplication().invokeLater(
                {
                    loadedApiToken = apiToken
                    loadedPassword = password
                    apiTokenField.text = apiToken
                    passwordField.text = password
                    secretsLoaded = true
                    secretsStatusLabel.text = ""
                    updateAuthState()
                },
                ModalityState.any(),
            )
        }
    }

    private enum class ProxyCard {
        NONE,
        CONFIGURED,
    }
}
