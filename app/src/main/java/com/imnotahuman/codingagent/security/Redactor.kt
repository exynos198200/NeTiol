package com.imnotahuman.codingagent.security

import java.util.Collections

/** Keeps a registry of live secrets and removes them from any text that may reach UI, logs or the model. */
object Redactor {
    private val secrets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    private val PATTERNS = listOf(
        Regex("""sk-or-v1-[A-Za-z0-9\-_]{16,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""(?i)(authorization\s*:\s*bearer\s+)\S+"""),
        Regex("""https://[^/\s:@]+:[^/\s@]+@""")
    )

    fun register(secret: String?) {
        if (!secret.isNullOrBlank() && secret.length >= 8) secrets.add(secret)
    }

    fun forget(secret: String?) {
        if (secret != null) secrets.remove(secret)
    }

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var out = text
        synchronized(secrets) {
            for (s in secrets) if (s.isNotBlank()) out = out.replace(s, "***REDACTED***")
        }
        for (p in PATTERNS) {
            out = when (p.pattern) {
                PATTERNS[3].pattern -> p.replace(out) { m -> m.groupValues[1] + "***REDACTED***" }
                PATTERNS[4].pattern -> p.replace(out, "https://***REDACTED***@")
                else -> p.replace(out, "***REDACTED***")
            }
        }
        return out
    }
}
