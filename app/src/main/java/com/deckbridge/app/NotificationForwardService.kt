package com.deckbridge.app

import android.app.Notification
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Watches notifications from a small set of messaging apps and forwards
 * each one as a new row in a Notion database. Everything else is ignored.
 *
 * Nothing is read, stored, or sent for any app not explicitly checked in
 * the settings screen.
 */
class NotificationForwardService : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences

    private val watchedPackages = mapOf(
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "com.discord" to "Discord"
    )

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val label = watchedPackages[sbn.packageName] ?: return
        if (!isWatched(label)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // Skip empty or summary-only notifications (e.g. "5 new messages").
        if (title.isBlank() && text.isBlank()) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val token = prefs.getString(Prefs.TOKEN, "")?.trim().orEmpty()
        val dbId = prefs.getString(Prefs.DB_ID, "")?.trim().orEmpty()
        if (token.isEmpty() || dbId.isEmpty()) return

        executor.execute {
            try {
                forwardToNotion(token, dbId, label, title, text)
            } catch (e: Exception) {
                Log.w("DeckBridge", "Forward failed: ${e.message}")
            }
        }
    }

    private fun isWatched(label: String): Boolean = when (label) {
        "Instagram" -> prefs.getBoolean(Prefs.WATCH_INSTAGRAM, true)
        "WhatsApp" -> prefs.getBoolean(Prefs.WATCH_WHATSAPP, true)
        "Discord" -> prefs.getBoolean(Prefs.WATCH_DISCORD, true)
        else -> false
    }

    /**
     * Creates one page in the target Notion database. The database is
     * expected to have these properties (see the setup README):
     *   Name    - title
     *   App     - rich_text
     *   Sender  - rich_text
     *   Message - rich_text
     *   Time    - date
     */
    private fun forwardToNotion(token: String, dbId: String, app: String, sender: String, message: String) {
        val url = URL("https://api.notion.com/v1/pages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Notion-Version", "2022-06-28")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val titleText = if (sender.isNotBlank()) "$app - $sender" else app

        val body = JSONObject().apply {
            put("parent", JSONObject().put("database_id", dbId))
            put("properties", JSONObject().apply {
                put("Name", textTitleProperty(titleText))
                put("App", richTextProperty(app))
                put("Sender", richTextProperty(sender))
                put("Message", richTextProperty(message))
                put("Time", JSONObject().put("date", JSONObject().put("start", isoTime)))
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            Log.w("DeckBridge", "Notion rejected the write ($code): $err")
        }
        conn.disconnect()
    }

    private fun textTitleProperty(value: String): JSONObject =
        JSONObject().put("title", org.json.JSONArray().put(
            JSONObject().put("text", JSONObject().put("content", value.take(2000)))
        ))

    private fun richTextProperty(value: String): JSONObject =
        JSONObject().put("rich_text", org.json.JSONArray().put(
            JSONObject().put("text", JSONObject().put("content", value.take(2000)))
        ))
}
