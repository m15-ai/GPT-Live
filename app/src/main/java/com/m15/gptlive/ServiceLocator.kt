package com.m15.gptlive

import android.content.Context
import android.media.AudioManager
import com.m15.gptlive.audio.DefaultAudioCapture
import com.m15.gptlive.data.db.AppDatabase
import com.m15.gptlive.data.repo.ConversationRepository
import com.m15.gptlive.prefs.ApiKeyProvider
import com.m15.gptlive.prefs.BuildConfigApiKeyProvider
import com.m15.gptlive.prefs.LocalPrefs
import com.m15.gptlive.realtime.OpenAiRealtimeClient
import com.m15.gptlive.realtime.RealtimeClient
import com.m15.gptlive.tools.BatteryTool
import com.m15.gptlive.tools.LiveTool
import com.m15.gptlive.tools.TimerTool
import okhttp3.OkHttpClient

object ServiceLocator {
    private var initialized = false

    lateinit var repo: ConversationRepository
    lateinit var realtimeClient: RealtimeClient
    lateinit var audio: DefaultAudioCapture
    lateinit var audioManager: AudioManager
    lateinit var apiKeyProvider: ApiKeyProvider
    lateinit var localPrefs: LocalPrefs
    /** Function tools offered to the delegated backend. Add yours here. */
    lateinit var tools: List<LiveTool>
    lateinit var appContext: Context

    fun init(ctx: Context) {
        if (initialized) return

        appContext = ctx.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val db = AppDatabase.get(appContext)
        repo = ConversationRepository(db)

        localPrefs = LocalPrefs(appContext)

        val okHttp = OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(15))
            .build()

        // Demo: key baked in at build time. Swap for a backend-backed provider in production.
        apiKeyProvider = BuildConfigApiKeyProvider()

        realtimeClient = OpenAiRealtimeClient(okHttp = okHttp)

        audio = DefaultAudioCapture(appContext)

        tools = listOf(BatteryTool(appContext), TimerTool(appContext))

        initialized = true
    }
}
