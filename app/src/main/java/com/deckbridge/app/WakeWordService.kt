package com.deckbridge.app

import ai.picovoice.eagle.Eagle
import ai.picovoice.eagle.EagleProfile
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * The always-on piece. Wake word ("Zenon") triggers speaker verification
 * against the enrolled voiceprint; only a match goes on to actually listen
 * for a command. Anyone else saying the wake word gets ignored.
 *
 * Runs as a foreground service (regular CPU listening, not a low-power
 * DSP like Apple's "Hey Siri" chip — see README for what that trade-off
 * means for battery).
 */
class WakeWordService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Listening for \"Zenon\"…"))
        startWakeWordDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.stop()
        porcupineManager?.delete()
        porcupineManager = null
    }

    private fun startWakeWordDetection() {
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val accessKey = prefs.getString(Prefs.PICOVOICE_KEY, "")?.trim().orEmpty()
        if (accessKey.isEmpty()) {
            updateNotification("No Picovoice AccessKey set — open the app to add one.")
            return
        }

        // Expects the custom wake word file trained at console.picovoice.ai
        // for "Zenon", downloaded for Android, and placed at
        // app/src/main/assets/zenon.ppn — see README.
        val keywordAssetPath = "zenon.ppn"

        try {
            val callback = PorcupineManagerCallback { _ ->
                onWakeWordDetected()
            }
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(keywordAssetPath))
                .setSensitivity(0.6f) // 0-1: higher catches more, but false-triggers more too
                .build(applicationContext, callback)
            porcupineManager?.start()
        } catch (e: Exception) {
            Log.e("DeckBridge", "Wake word init failed: ${e.message}")
            updateNotification("Wake word setup failed — check zenon.ppn is in assets/.")
        }
    }

    private fun onWakeWordDetected() {
        if (listening) return // ignore re-triggers while already handling one
        listening = true
        updateNotification("Heard the wake word — checking it's you…")

        Thread {
            val verified = verifySpeaker()
            if (!verified) {
                updateNotification("Not a voice match — ignoring. Listening for \"Zenon\"…")
                listening = false
                return@Thread
            }
            updateNotification("It's you — go ahead.")
            captureCommandAndRespond()
        }.start()
    }

    /** Records ~2.5s right after the wake word and compares it to the enrolled profile. */
    @SuppressLint("MissingPermission")
    private fun verifySpeaker(): Boolean {
        val profileFile = VoiceEnroll.profileFile(applicationContext)
        if (!profileFile.exists()) {
            Log.w("DeckBridge", "No enrolled voice profile — treating as unverified.")
            return false
        }

        var eagle: Eagle? = null
        var recorder: AudioRecord? = null
        return try {
            val accessKey = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getString(Prefs.PICOVOICE_KEY, "")?.trim().orEmpty()
            // NOTE: verify this constructor against the current Eagle Android
            // Javadoc — reconstructing a profile from saved bytes is a
            // documented capability, but check the exact call shape.
            val profile = EagleProfile(profileFile.readBytes())
            eagle = Eagle.Builder()
                .setAccessKey(accessKey)
                .setSpeakerProfiles(arrayOf(profile))
                .build(applicationContext)

            val sampleRate = eagle.sampleRate
            val frameLength = eagle.frameLength
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate)
            )
            recorder.startRecording()

            var best = 0f
            val totalFrames = (sampleRate * 2.5).toInt() / frameLength
            val frame = ShortArray(frameLength)
            repeat(totalFrames) {
                var read = 0
                while (read < frame.size) {
                    val n = recorder.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == frame.size) {
                    val scores = eagle.process(frame)
                    if (scores.isNotEmpty() && scores[0] > best) best = scores[0]
                }
            }
            recorder.stop()

            // Threshold picked conservatively; tune after real-world testing —
            // too low lets others in, too high locks you out on a bad mic day.
            best >= 0.6f
        } catch (e: Exception) {
            Log.e("DeckBridge", "Speaker verification failed: ${e.message}")
            false
        } finally {
            try { recorder?.release() } catch (_: Exception) {}
            try { eagle?.delete() } catch (_: Exception) {}
        }
    }

    /** Uses Android's built-in speech recognizer to capture the spoken command as text. */
    private fun captureCommandAndRespond() {
        android.os.Handler(mainLooper).post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    recognizer.destroy()
                    handleCommand(text)
                }
                override fun onError(error: Int) {
                    recognizer.destroy()
                    updateNotification("Didn't catch that. Listening for \"Zenon\"…")
                    listening = false
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        }
    }

    private fun handleCommand(commandText: String) {
        if (commandText.isBlank()) {
            updateNotification("Didn't catch that. Listening for \"Zenon\"…")
            listening = false
            return
        }
        updateNotification("Thinking about: \"$commandText\"")
        AiBrain.respond(applicationContext, commandText) { answer ->
            updateNotification("Listening for \"Zenon\"…")
            ElevenLabsTts.speak(applicationContext, answer)
            listening = false
        }
    }

    // ---- foreground notification plumbing ----

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Zenon", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zenon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "zenon_wake_word"
        private const val NOTIF_ID = 42
    }
}
