package com.kerybotu.derpibooru.mirror.network

/** Derives the source project's tested Anubis identity without claiming a newer browser. */
internal object AnubisEnvironmentPolicy {
    private val chromeVersionToken = Regex("\\s+Chrome/\\d+(?:\\.\\d+){0,3}", RegexOption.IGNORE_CASE)

    fun versionlessUserAgent(runtimeUserAgent: String): String =
        chromeVersionToken.replace(runtimeUserAgent, "")

    fun isAuthCookieName(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "techaro.lol-anubis" ||
            normalized.startsWith("techaro.lol-anubis-auth-") ||
            normalized.endsWith("-anubis")
    }
}
