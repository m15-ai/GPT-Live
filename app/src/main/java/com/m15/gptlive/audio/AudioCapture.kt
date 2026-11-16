package com.m15.gptlive.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.math.max

interface AudioCapture {
    fun start(onPcm: (ShortArray) -> Unit)
    fun stop()
}

/**
 * Captures microphone audio as 24 kHz PCM-16 mono frames (~20 ms each).
 *
 * The app always plays through the loudspeaker, so the mic uses the
 * VOICE_COMMUNICATION source with acoustic echo cancellation and noise
 * suppression enabled. Without AEC the model hears its own voice and may
 * transcribe or interrupt itself. The trade-off is that very quiet barge-ins
 * during playback can be attenuated by the canceller.
 *
 * The reader runs on a dedicated URGENT_AUDIO thread with a ~200 ms record
 * buffer, so a busy CPU cannot cause input overruns (dropped mic audio).
 *
 * 24 kHz is required by GPT-Live. If a device does not support
 * AudioRecord at 24000 Hz, initialisation fails and an error is logged.
 */
class DefaultAudioCapture(
    @Suppress("unused") private val context: Context,
    private val sampleRate: Int = 24_000,
) : AudioCapture {

    private companion object {
        const val TAG = "AudioCapture"
        const val FRAME_MS = 20
        const val RECORD_BUFFER_MS = 200
    }

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onPcm: (ShortArray) -> Unit) {
        stop()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = max(minBuf * 2, sampleRate * 2 * RECORD_BUFFER_MS / 1000)

        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed at $sampleRate Hz — device may not support this rate")
            rec.release()
            return
        }
        record = rec
        attachEffects(rec.audioSessionId)
        Log.i(TAG, "Audio session=${rec.audioSessionId} buf=${bufSize}B (min=$minBuf) aec=${aec?.enabled} ns=${ns?.enabled}")

        rec.startRecording()
        running = true
        thread = Thread({ captureLoop(rec, onPcm) }, "gptlive-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** Enable platform AEC/NS on the record session when the device offers them. */
    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = try {
                AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
            } catch (t: Throwable) { Log.w(TAG, "AEC create failed: ${t.message}"); null }
        } else {
            Log.w(TAG, "AcousticEchoCanceler not available on this device")
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = try {
                NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
            } catch (t: Throwable) { Log.w(TAG, "NS create failed: ${t.message}"); null }
        }
    }

    private fun captureLoop(rec: AudioRecord, onPcm: (ShortArray) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameSamples = sampleRate * FRAME_MS / 1000 // 480 samples @ 24 kHz
        val frame = ShortArray(frameSamples)
        while (running) {
            val n = rec.read(frame, 0, frame.size) // blocking
            when {
                n == frame.size -> onPcm(frame)
                n > 0 -> onPcm(frame.copyOf(n))
                n < 0 -> {
                    Log.w(TAG, "AudioRecord read error: $n")
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                }
            }
        }
    }

    override fun stop() {
        running = false
        val rec = record
        // stop() unblocks a pending read(); join before release so the loop never touches a dead object.
        try { rec?.stop() } catch (_: Throwable) {}
        thread?.let { t -> try { t.join(500) } catch (_: InterruptedException) {} }
        thread = null
        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        aec = null
        ns = null
        try { rec?.release() } catch (_: Throwable) {}
        record = null
    }
}
