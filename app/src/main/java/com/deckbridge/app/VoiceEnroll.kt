package com.deckbridge.app

import ai.picovoice.eagle.EagleProfiler
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.Executors

/**
 * Walks the user through recording a few seconds of their own voice and
 * turns it into an Eagle speaker profile, saved to internal storage.
 * That profile is later used by WakeWordService to check "was that
 * actually them?" before treating a wake-word hit as real.
 */
object VoiceEnroll {

    private val executor = Executors.newSingleThreadExecutor()

    fun profileFile(context: Context): File = File(context.filesDir, "eagle_profile.bin")

    /**
     * onProgress is called with 0-100 as enrollment proceeds.
     * onDone is called with true/false for success, and a message.
     */
    @SuppressLint("MissingPermission") // caller must have already requested RECORD_AUDIO
    fun enroll(
        context: Context,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val accessKey = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getString(Prefs.PICOVOICE_KEY, "")?.trim().orEmpty()
        if (accessKey.isEmpty()) {
            onDone(false, "Add a Picovoice AccessKey first.")
            return
        }

        executor.execute {
            var profiler: EagleProfiler? = null
            var recorder: AudioRecord? = null
            try {
                profiler = EagleProfiler.Builder()
                    .setAccessKey(accessKey)
                    .build(context)

                val sampleRate = profiler.sampleRate
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, sampleRate) // at least ~1s of buffer
                )
                recorder.startRecording()

                val frameSize = profiler.minEnrollSamples
                val frame = ShortArray(frameSize)
                var percentage = 0f

                // Caps enrollment at ~45 seconds of audio so a stuck mic
                // or silent room doesn't loop forever.
                val maxFrames = 45 * sampleRate / frameSize
                var frames = 0

                while (percentage < 100f && frames < maxFrames) {
                    var read = 0
                    while (read < frame.size) {
                        val n = recorder.read(frame, read, frame.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < frame.size) break // mic gave up early

                    percentage = profiler.enroll(frame)
                    frames++
                    onProgress(percentage.toInt())
                }

                recorder.stop()

                if (percentage >= 100f) {
                    // NOTE: verify `.bytes` against the current Eagle Android
                    // Javadoc (picovoice.ai/docs/api/eagle-android) — some
                    // versions expose this as `.getBytes()` instead.
                    val exported = profiler.export()
                    profileFile(context).writeBytes(exported.bytes)
                    context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(Prefs.EAGLE_ENROLLED, true).apply()
                    onDone(true, "Voice enrolled.")
                } else {
                    onDone(false, "Didn't get enough clean audio — try again somewhere quieter.")
                }
            } catch (e: Exception) {
                onDone(false, "Enrollment failed: ${e.message}")
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
                try { profiler?.delete() } catch (_: Exception) {}
            }
        }
    }
}
