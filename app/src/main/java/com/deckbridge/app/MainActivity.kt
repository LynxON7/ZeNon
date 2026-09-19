package com.deckbridge.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val inputToken = findViewById<EditText>(R.id.inputToken)
        val inputDb = findViewById<EditText>(R.id.inputDatabaseId)
        val chkInstagram = findViewById<CheckBox>(R.id.chkInstagram)
        val chkWhatsapp = findViewById<CheckBox>(R.id.chkWhatsapp)
        val chkDiscord = findViewById<CheckBox>(R.id.chkDiscord)
        val status = findViewById<TextView>(R.id.txtStatus)

        inputToken.setText(prefs.getString(Prefs.TOKEN, ""))
        inputDb.setText(prefs.getString(Prefs.DB_ID, ""))
        chkInstagram.isChecked = prefs.getBoolean(Prefs.WATCH_INSTAGRAM, true)
        chkWhatsapp.isChecked = prefs.getBoolean(Prefs.WATCH_WHATSAPP, true)
        chkDiscord.isChecked = prefs.getBoolean(Prefs.WATCH_DISCORD, true)

        val inputGeminiKey = findViewById<EditText>(R.id.inputGeminiKey)
        val inputLocalModelPath = findViewById<EditText>(R.id.inputLocalModelPath)
        val inputTestPrompt = findViewById<EditText>(R.id.inputTestPrompt)
        val txtAiAnswer = findViewById<TextView>(R.id.txtAiAnswer)

        inputGeminiKey.setText(prefs.getString(Prefs.GEMINI_KEY, ""))
        inputLocalModelPath.setText(prefs.getString(Prefs.LOCAL_MODEL_PATH, ""))

        findViewById<Button>(R.id.btnSaveBrain).setOnClickListener {
            prefs.edit()
                .putString(Prefs.GEMINI_KEY, inputGeminiKey.text.toString().trim())
                .putString(Prefs.LOCAL_MODEL_PATH, inputLocalModelPath.text.toString().trim())
                .apply()
            status.text = "AI settings saved."
        }

        findViewById<Button>(R.id.btnTestAsk).setOnClickListener {
            val prompt = inputTestPrompt.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            txtAiAnswer.text = "Thinking…"
            AiBrain.respond(this, prompt) { result ->
                runOnUiThread { txtAiAnswer.text = result }
            }
        }

        findViewById<Button>(R.id.btnGrantAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ---- Zenon: wake word + voice ----
        val inputPicovoiceKey = findViewById<EditText>(R.id.inputPicovoiceKey)
        val inputElevenLabsKey = findViewById<EditText>(R.id.inputElevenLabsKey)
        val inputElevenLabsVoice = findViewById<EditText>(R.id.inputElevenLabsVoice)
        val txtEnrollStatus = findViewById<TextView>(R.id.txtEnrollStatus)

        inputPicovoiceKey.setText(prefs.getString(Prefs.PICOVOICE_KEY, ""))
        inputElevenLabsKey.setText(prefs.getString(Prefs.ELEVENLABS_KEY, ""))
        inputElevenLabsVoice.setText(prefs.getString(Prefs.ELEVENLABS_VOICE_ID, ""))
        if (prefs.getBoolean(Prefs.EAGLE_ENROLLED, false)) {
            txtEnrollStatus.text = "Voice already enrolled. Re-run to replace it."
        }

        findViewById<Button>(R.id.btnSaveVoiceSettings).setOnClickListener {
            prefs.edit()
                .putString(Prefs.PICOVOICE_KEY, inputPicovoiceKey.text.toString().trim())
                .putString(Prefs.ELEVENLABS_KEY, inputElevenLabsKey.text.toString().trim())
                .putString(Prefs.ELEVENLABS_VOICE_ID, inputElevenLabsVoice.text.toString().trim())
                .apply()
            status.text = "Voice settings saved."
        }

        findViewById<Button>(R.id.btnEnroll).setOnClickListener {
            if (!hasMicPermission()) {
                requestMicPermission()
                txtEnrollStatus.text = "Grant microphone access, then tap Enroll again."
                return@setOnClickListener
            }
            txtEnrollStatus.text = "Starting… keep talking naturally until this says done."
            VoiceEnroll.enroll(
                this,
                onProgress = { pct -> runOnUiThread { txtEnrollStatus.text = "Enrolling… $pct%" } },
                onDone = { success, message -> runOnUiThread { txtEnrollStatus.text = message } }
            )
        }

        findViewById<Button>(R.id.btnStartZenon).setOnClickListener {
            if (!hasMicPermission()) {
                requestMicPermission()
                status.text = "Grant microphone access, then tap Start again."
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            status.text = "Zenon is listening in the background."
        }

        findViewById<Button>(R.id.btnStopZenon).setOnClickListener {
            stopService(Intent(this, WakeWordService::class.java))
            status.text = "Zenon stopped."
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit()
                .putString(Prefs.TOKEN, inputToken.text.toString().trim())
                .putString(Prefs.DB_ID, inputDb.text.toString().trim())
                .putBoolean(Prefs.WATCH_INSTAGRAM, chkInstagram.isChecked)
                .putBoolean(Prefs.WATCH_WHATSAPP, chkWhatsapp.isChecked)
                .putBoolean(Prefs.WATCH_DISCORD, chkDiscord.isChecked)
                .apply()
            status.text = "Saved. Make sure notification access is granted above."
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
    }
}

/** Shared preference keys, read by both the activity and the listener service. */
object Prefs {
    const val NAME = "deck_bridge_prefs"
    const val TOKEN = "notion_token"
    const val DB_ID = "notion_db_id"
    const val WATCH_INSTAGRAM = "watch_instagram"
    const val WATCH_WHATSAPP = "watch_whatsapp"
    const val WATCH_DISCORD = "watch_discord"
    const val GEMINI_KEY = "gemini_api_key"
    const val LOCAL_MODEL_PATH = "local_model_path"
    const val PICOVOICE_KEY = "picovoice_access_key"
    const val EAGLE_ENROLLED = "eagle_enrolled"
    const val ELEVENLABS_KEY = "elevenlabs_api_key"
    const val ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
}
