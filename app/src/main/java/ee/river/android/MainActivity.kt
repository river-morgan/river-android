package ee.river.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var autoStartedThisLaunch = false
    private lateinit var logView: TextView
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private val prefs by lazy { getSharedPreferences("river", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        buildUi()
        requestRuntimePermissions()
        startService(Intent(this, RiverForegroundService::class.java))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        abandonAudioFocus()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.UK
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "River"
            textSize = 34f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Android bridge test harness: voice capture, audio focus, TTS acknowledgement, Hermes POST."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        endpointInput = EditText(this).apply {
            hint = "Hermes endpoint URL (optional for first test)"
            setSingleLine(true)
            setText(prefs.getString("endpoint", "") ?: "")
        }
        tokenInput = EditText(this).apply {
            hint = "Bearer token (optional; stored only on phone)"
            setSingleLine(true)
            setText(prefs.getString("token", "") ?: "")
        }
        val save = Button(this).apply {
            text = "Save endpoint"
            setOnClickListener {
                prefs.edit().putString("endpoint", endpointInput.text.toString().trim()).putString("token", tokenInput.text.toString()).apply()
                log("Saved endpoint settings.")
            }
        }
        val ask = Button(this).apply {
            text = "Start River voice session"
            textSize = 18f
            setOnClickListener { startVoiceCapture() }
        }
        val testAck = Button(this).apply {
            text = "Speak test acknowledgement"
            setOnClickListener { speakAck("Got it — River is listening.") }
        }
        val settings = Button(this).apply {
            text = "Open app notification/settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        logView = TextView(this).apply {
            text = "Log:\n"
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(ask, wide())
        root.addView(testAck, wide())
        root.addView(endpointInput, wide())
        root.addView(tokenInput, wide())
        root.addView(save, wide())
        root.addView(settings, wide())
        root.addView(logView, wide())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 77)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = when {
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.data?.scheme == "river" -> intent.data?.getQueryParameter("text")
            intent.action == ACTION_ASK -> intent.getStringExtra("text")
            else -> null
        }
        if (!text.isNullOrBlank()) {
            log("Intent text received from ${intent.action ?: "unknown"}.")
            processCapturedText(text)
        } else if (shouldAutoStart(intent)) {
            log("Auto-starting voice capture from ${intent.action ?: "launcher"}.")
            autoStartedThisLaunch = true
            rootViewPostDelayed { startVoiceCapture() }
        }
    }

    private fun shouldAutoStart(intent: Intent): Boolean {
        if (autoStartedThisLaunch) return false
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) return true
        return intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }

    private fun rootViewPostDelayed(block: () -> Unit) {
        window.decorView.postDelayed(block, 500)
    }

    private fun startVoiceCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions(); log("Microphone permission needed."); return
        }
        requestTransientAudioFocus()
        speakAck("River is listening.")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell River")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { @Suppress("DEPRECATION") startActivityForResult(intent, REQ_SPEECH) }
        catch (e: Exception) { log("Speech recognizer unavailable: ${e.message}"); abandonAudioFocus() }
    }

    @Deprecated("Deprecated by Android; good enough for this MVP harness.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (resultCode == RESULT_OK && !spoken.isNullOrBlank()) processCapturedText(spoken)
            else { log("No speech captured."); abandonAudioFocus() }
        }
    }

    private fun processCapturedText(text: String) {
        log("Captured: $text")
        speakAck("Got it — I’ll send that to River.")
        postToHermes(text)
    }

    private fun requestTransientAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION") audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
        focusRequest = null
    }

    private fun speakAck(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "river-ack-${System.currentTimeMillis()}")
    }

    private fun postToHermes(text: String) {
        val endpoint = endpointInput.text.toString().trim().ifEmpty { prefs.getString("endpoint", "") ?: "" }
        val token = tokenInput.text.toString().ifEmpty { prefs.getString("token", "") ?: "" }
        if (endpoint.isBlank()) {
            log("No Hermes endpoint configured. Local capture/audio test complete.")
            abandonAudioFocus()
            return
        }
        thread {
            try {
                val body = "{\"source\":\"river-android\",\"text\":${json(text)}}"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                runOnUiThread {
                    log("Hermes POST $code ${response.take(240)}")
                    speakAck(if (code in 200..299) "Sent to River." else "River endpoint returned an error.")
                    abandonAudioFocus()
                }
            } catch (e: Exception) {
                runOnUiThread { log("Hermes POST failed: ${e.message}"); speakAck("I captured it, but could not reach River."); abandonAudioFocus() }
            }
        }
    }

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    private fun log(msg: String) { logView.append("\n${java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())}  $msg") }

    companion object {
        const val ACTION_ASK = "ee.river.android.ASK"
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_SPEECH = 501
    }
}
