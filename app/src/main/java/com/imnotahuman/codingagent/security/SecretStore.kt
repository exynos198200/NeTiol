package com.imnotahuman.codingagent.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secrets are stored in EncryptedSharedPreferences backed by an Android Keystore master key.
 * Nothing here is ever written to plaintext prefs, logs or agent prompts.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "agent_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        Redactor.register(read(KEY_OPENROUTER))
        Redactor.register(read(KEY_GITHUB_PAT))
    }

    private fun read(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    var openRouterKey: String?
        get() = read(KEY_OPENROUTER)
        set(value) = store(KEY_OPENROUTER, value)

    var githubPat: String?
        get() = read(KEY_GITHUB_PAT)
        set(value) = store(KEY_GITHUB_PAT, value)

    private fun store(key: String, value: String?) {
        val old = read(key)
        Redactor.forget(old)
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value.trim())
        }.apply()
        Redactor.register(value?.trim())
    }

    fun hasOpenRouterKey(): Boolean = openRouterKey != null
    fun hasGithubPat(): Boolean = githubPat != null

    /** Only a masked hint is ever exposed to the UI. */
    fun mask(value: String?): String = when {
        value.isNullOrBlank() -> "not set"
        value.length <= 8 -> "********"
        else -> value.take(4) + "…" + value.takeLast(4)
    }

    private companion object {
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_GITHUB_PAT = "github_pat"
    }
}
