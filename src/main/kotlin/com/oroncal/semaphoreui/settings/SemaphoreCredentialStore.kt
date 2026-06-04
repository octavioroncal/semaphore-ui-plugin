package com.oroncal.semaphoreui.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class SemaphoreCredentialStore {
    private val passwordSafe = PasswordSafe.instance

    fun hasApiToken(): Boolean = getApiToken() != null

    fun getApiToken(): String? = passwordSafe.get(API_TOKEN_ATTRIBUTES)?.getPasswordAsString()?.takeIf { it.isNotBlank() }

    fun setApiToken(value: String?) {
        setSecret(API_TOKEN_ATTRIBUTES, value)
    }

    fun hasPassword(): Boolean = getPassword() != null

    fun getPassword(): String? = passwordSafe.get(USER_PASSWORD_ATTRIBUTES)?.getPasswordAsString()?.takeIf { it.isNotBlank() }

    fun setPassword(value: String?) {
        setSecret(USER_PASSWORD_ATTRIBUTES, value)
    }

    private fun setSecret(attributes: CredentialAttributes, value: String?) {
        val credentials = value?.takeIf { it.isNotBlank() }?.let { Credentials(SECRET_USER, it) }
        passwordSafe.set(attributes, credentials)
    }

    private companion object {
        private const val SECRET_USER = "SemaphoreUI"
        private val API_TOKEN_ATTRIBUTES = CredentialAttributes(generateServiceName("SemaphoreUI", "api-token"))
        private val USER_PASSWORD_ATTRIBUTES = CredentialAttributes(generateServiceName("SemaphoreUI", "user-password"))
    }
}
