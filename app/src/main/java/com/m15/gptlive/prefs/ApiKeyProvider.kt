package com.m15.gptlive.prefs

import com.m15.gptlive.BuildConfig

/**
 * Supplies the bearer token used to open the GPT-Live WebSocket.
 *
 * This is the one seam to replace before shipping: a production app should
 * never embed a long-lived OpenAI key. Implement this interface with a call to
 * your own backend that mints a short-lived ephemeral token per session.
 */
fun interface ApiKeyProvider {
    suspend fun getApiKey(): String
}

/**
 * Demo implementation: the key from `local.properties` baked into BuildConfig
 * at compile time. Anyone with the APK can extract it, so use a key created
 * just for this app and rotate it if the build leaves your device.
 */
class BuildConfigApiKeyProvider : ApiKeyProvider {
    override suspend fun getApiKey(): String {
        val key = BuildConfig.OPENAI_API_KEY.trim()
        check(key.isNotBlank()) { "OPENAI_API_KEY is empty. Set it in local.properties and rebuild." }
        return key
    }
}
