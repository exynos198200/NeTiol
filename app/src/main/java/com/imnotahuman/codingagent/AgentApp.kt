package com.imnotahuman.codingagent

import android.app.Application
import com.imnotahuman.codingagent.ai.OpenRouterClient
import com.imnotahuman.codingagent.data.SessionStore
import com.imnotahuman.codingagent.data.SettingsRepository
import com.imnotahuman.codingagent.git.GitService
import com.imnotahuman.codingagent.github.GitHubApi
import com.imnotahuman.codingagent.security.SecretStore
import com.imnotahuman.codingagent.workspace.WorkspaceManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

class AgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI: one place that builds every long-lived dependency. */
class AppContainer(app: Application) {

    val secrets = SecretStore(app)
    val settings = SettingsRepository(app)
    val workspace = WorkspaceManager(app)
    val sessions = SessionStore(app)

    /** HTTPS only; cleartext is disabled both here and in the manifest. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val ai = OpenRouterClient(http) { secrets.openRouterKey }
    val github = GitHubApi(http) { secrets.githubPat }
    val git = GitService { secrets.githubPat }
}
