package com.deckbridge.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Routes a question to whichever brain is actually available right now:
 *   - online  -> Gemini API (free tier)
 *   - offline -> on-device Gemma model, if one has been set up
 *   - neither -> says so plainly, rather than pretending to answer
 *
 * This does not use Claude. It's built around Gemini's free tier plus an
 * optional local model, per the cost trade-off this project chose.
 */
object AiBrain {

    private val executor = Executors.newSingleThreadExecutor()

    // Stable as of the model's 7 May 2026 release; the Gemini 2.5 line this
    // replaces is being retired 16 Oct 2026, so this is the current safe
    // default. Check ai.google.dev/gemini-api/docs/models if this ever
    // starts failing — Google renames/retires model IDs over time.
    private const val GEMINI_MODEL = "gemini-3.1-flash-lite"

    fun respond(context: Context, prompt: String, callback: (String) -> Unit) {
        executor.execute {
            val result = try {
                if (isOnline(context)) {
                    askGemini(context, prompt)
                } else {
                    askLocal(context, prompt)
                }
            } catch (e: Exception) {
                "Something went wrong asking that: ${e.message}"
            }
            callback(result)
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun askGemini(context: Context, prompt: String): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(Prefs.GEMINI_KEY, "")?.trim().orEmpty()
        if (key.isEmpty()) return "No Gemini API key set yet — add one in AI brain settings."

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$key")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            return "Gemini request failed ($code). ${err ?: ""}".trim()
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        return try {
            val json = JSONObject(responseText)
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            "Got a response but couldn't parse it — Gemini's response shape may have changed."
        }
    }

    /**
     * On-device fallback via MediaPipe's LLM Inference API. This is left as
     * a clearly-marked stub rather than a guessed implementation: MediaPipe's
     * Kotlin API for this has changed across versions, and getting the class
     * names wrong here would look like it works and then silently fail.
     *
     * To wire this up for real:
     *   1. Add to app/build.gradle: implementation 'com.google.mediapipe:tasks-genai:<latest>'
     *   2. Follow: ai.google.dev/gemma/docs/integrations/mobile
     *   3. Download a Gemma .task model (requires accepting Google's license
     *      on Kaggle or Hugging Face — can't be automated) and push it to
     *      the phone, e.g.: adb push gemma-3-1b-it.task /data/local/tmp/
     *   4. Point "Local model file path" in settings at that path, and
     *      replace the body of this function with a real LlmInference call.
     */
    private fun askLocal(context: Context, prompt: String): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val modelPath = prefs.getString(Prefs.LOCAL_MODEL_PATH, "")?.trim().orEmpty()
        if (modelPath.isEmpty()) {
            return "No signal, and no local model set up yet — see the README for on-device setup."
        }
        return "No signal. A local model path is set ($modelPath) but the on-device engine isn't wired up yet — see AiBrain.kt for the last step."
    }
}
