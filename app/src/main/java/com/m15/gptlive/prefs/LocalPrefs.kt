package com.m15.gptlive.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user settings (system prompt, voice, delegation model, tool switches, store) between sessions.
 */
class LocalPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getSystemMessage(): String {
        val stored = sp.getString(KEY_SYSTEM_MESSAGE, null)
        return if (stored.isNullOrBlank()) DEFAULT_SYSTEM_MESSAGE else stored
    }

    fun setSystemMessage(message: String) =
        sp.edit().putString(KEY_SYSTEM_MESSAGE, message).apply()

    fun getVoice(): String {
        val stored = sp.getString(KEY_VOICE, null)
        return if (stored.isNullOrBlank()) LiveVoices.DEFAULT else stored
    }

    fun setVoice(voice: String) =
        sp.edit().putString(KEY_VOICE, voice).apply()

    fun getDelegationModel(): String {
        val stored = sp.getString(KEY_DELEGATION_MODEL, null)
        return if (stored.isNullOrBlank()) DelegationModels.DEFAULT else stored
    }

    fun setDelegationModel(model: String) =
        sp.edit().putString(KEY_DELEGATION_MODEL, model.trim()).apply()

    /** Names of tools the user has switched off. Unknown names are ignored, so new tools default to on. */
    fun getDisabledTools(): Set<String> =
        sp.getStringSet(KEY_DISABLED_TOOLS, null)?.toSet() ?: emptySet()

    fun setDisabledTools(names: Set<String>) =
        sp.edit().putStringSet(KEY_DISABLED_TOOLS, names).apply()

    /** GPT-Live `store`: keep a server-side recording so the session can be forked or resumed. */
    fun getStoreEnabled(): Boolean = sp.getBoolean(KEY_STORE, false)
    fun setStoreEnabled(on: Boolean) = sp.edit().putBoolean(KEY_STORE, on).apply()

    companion object {
        private const val FILE_NAME = "gptlive_local_prefs"
        private const val KEY_SYSTEM_MESSAGE = "system_message"
        private const val KEY_VOICE = "voice"
        private const val KEY_DELEGATION_MODEL = "delegation_model"
        private const val KEY_DISABLED_TOOLS = "disabled_tools"
        private const val KEY_STORE = "store"

        const val DEFAULT_SYSTEM_MESSAGE =
            "You are a friendly and helpful voice assistant. " +
            "Your responses are spoken aloud via text-to-speech. " +
            "Keep responses short and conversational — 1 to 3 sentences max. " +
            "Never use bullet points, numbered lists, markdown, emojis, or special formatting. " +
            "Speak in plain, natural sentences like a real conversation. " +
            "If a topic needs more detail, offer to explain further rather than dumping everything at once."
    }
}
