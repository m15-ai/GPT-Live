package com.m15.gptlive.prefs

/** GPT-Live voice API names and short labels for the picker. */
data class LiveVoice(val id: String, val label: String, val detail: String)

/**
 * Built-in GPT-Live voices (docs: live-conversations#voice-options, 2026-10-16).
 * "Natural" voices are modelled on a recorded speaker; the rest are generated.
 * A custom voice id (`voice_...`) from prompt-to-voice or a cloned voice is
 * accepted anywhere a voice id is, see [isCustom].
 */
object LiveVoices {
    const val DEFAULT = "marin"
    const val CUSTOM_PREFIX = "voice_"

    val all: List<LiveVoice> = listOf(
        LiveVoice("marin", "Marin", "Default"),
        // English
        LiveVoice("quartz", "Quartz", "English · Australian · Feminine"),
        LiveVoice("ripple", "Ripple", "English · Australian · Masculine · Natural"),
        LiveVoice("vesper", "Vesper", "English · British · Masculine · Natural"),
        LiveVoice("willow", "Willow", "English · Irish · Feminine · Natural"),
        LiveVoice("stone", "Stone", "English · Irish · Masculine · Natural"),
        LiveVoice("gleam", "Gleam", "English · North American · Feminine · Natural"),
        LiveVoice("meridian", "Meridian", "English · North American · Masculine · Natural"),
        LiveVoice("beacon", "Beacon", "English · Filipino · Masculine"),
        LiveVoice("delta", "Delta", "English · Southern U.S. · Feminine"),
        LiveVoice("cinder", "Cinder", "English · Southern U.S. · Masculine"),
        // Portuguese
        LiveVoice("bossa", "Bossa", "Portuguese · Brazilian · Feminine · Natural"),
        LiveVoice("tempo", "Tempo", "Portuguese · Brazilian · Masculine · Natural"),
        // Added 2026-10-16
        LiveVoice("nuri", "Nuri", "German · Feminine · Natural"),
        LiveVoice("juni", "Juni", "German · Feminine · Natural"),
        LiveVoice("flitz", "Flitz", "German · Masculine · Natural"),
        LiveVoice("brise", "Brise", "French · Feminine · Natural"),
        LiveVoice("sillage", "Sillage", "French · Masculine · Natural"),
        LiveVoice("nira", "Nira", "Hindi · Feminine · Natural"),
        LiveVoice("harema", "Harema", "Japanese · Feminine · Natural"),
        LiveVoice("shida", "Shida", "Japanese · Masculine · Natural"),
        LiveVoice("noeul", "Noeul", "Korean · Feminine · Natural"),
    )

    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    fun labelFor(id: String): String = when {
        isCustom(id) -> "Custom ($id)"
        else -> all.firstOrNull { it.id == id }?.label ?: id.replaceFirstChar { it.uppercase() }
    }
}
