package com.deckbridge.app

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-speech via ElevenLabs. Needs an API key and a voice ID, both set
 * in the app's settings screen — pick a voice at elevenlabs.io/app/voice-library.
 */
object ElevenLabsTts {

    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(Prefs.ELEVENLABS_KEY, "")?.trim().orEmpty()
        val voiceId = prefs.getString(Prefs.ELEVENLABS_VOICE_ID, "")?.trim().orEmpty()
        if (key.isEmpty() || voiceId.isEmpty()) {
            Log.w("DeckBridge", "ElevenLabs not configured — skipping speech.")
            return
        }

        try {
            val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("xi-api-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "audio/mpeg")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            val body = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w("DeckBridge", "ElevenLabs request failed ($code)")
                return
            }

            val outFile = File(context.cacheDir, "zenon_speech.mp3")
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { out: OutputStream -> input.copyTo(out) }
            }
            conn.disconnect()

            val player = MediaPlayer()
            player.setDataSource(outFile.absolutePath)
            player.setOnCompletionListener { it.release() }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.w("DeckBridge", "TTS playback failed: ${e.message}")
        }
    }
}
