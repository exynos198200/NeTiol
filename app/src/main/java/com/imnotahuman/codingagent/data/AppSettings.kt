package com.imnotahuman.codingagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val model: String = "anthropic/claude-3.5-sonnet",
    val maxSteps: Int = 16,
    val stepTimeoutSec: Int = 120,
    val maxToolOutputChars: Int = 12_000,
    val temperature: Float = 0.2f,
    val confirmDangerous: Boolean = true,
    val lastProjectId: String? = null,
    val githubOwner: String = "",
    val githubRepo: String = "",
    val workflowFile: String = "build-apk.yml",
    val workflowRef: String = "main"
)

/** Plain (non-secret) settings. Secrets live only in [com.imnotahuman.codingagent.security.SecretStore]. */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state

    val current: Settings get() = _state.value

    private fun load(): Settings = Settings(
        model = prefs.getString("model", null) ?: "anthropic/claude-3.5-sonnet",
        maxSteps = prefs.getInt("maxSteps", 16),
        stepTimeoutSec = prefs.getInt("stepTimeoutSec", 120),
        maxToolOutputChars = prefs.getInt("maxToolOutputChars", 12_000),
        temperature = prefs.getFloat("temperature", 0.2f),
        confirmDangerous = prefs.getBoolean("confirmDangerous", true),
        lastProjectId = prefs.getString("lastProjectId", null),
        githubOwner = prefs.getString("githubOwner", "") ?: "",
        githubRepo = prefs.getString("githubRepo", "") ?: "",
        workflowFile = prefs.getString("workflowFile", null) ?: "build-apk.yml",
        workflowRef = prefs.getString("workflowRef", null) ?: "main"
    )

    fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        prefs.edit()
            .putString("model", next.model)
            .putInt("maxSteps", next.maxSteps)
            .putInt("stepTimeoutSec", next.stepTimeoutSec)
            .putInt("maxToolOutputChars", next.maxToolOutputChars)
            .putFloat("temperature", next.temperature)
            .putBoolean("confirmDangerous", next.confirmDangerous)
            .putString("lastProjectId", next.lastProjectId)
            .putString("githubOwner", next.githubOwner)
            .putString("githubRepo", next.githubRepo)
            .putString("workflowFile", next.workflowFile)
            .putString("workflowRef", next.workflowRef)
            .apply()
        _state.value = next
    }
}

object KnownModels {
    val list = listOf(
        "anthropic/claude-3.5-sonnet",
        "anthropic/claude-3.5-haiku",
        "openai/gpt-4o-mini",
        "openai/gpt-4o",
        "google/gemini-2.0-flash-001",
        "deepseek/deepseek-chat",
        "qwen/qwen-2.5-coder-32b-instruct",
        "meta-llama/llama-3.3-70b-instruct"
    )
}
