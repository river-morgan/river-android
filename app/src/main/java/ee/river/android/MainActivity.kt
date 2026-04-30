package ee.river.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var autoStartedThisLaunch = false
    private lateinit var logView: TextView
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var askButton: Button
    private val prefs by lazy { getSharedPreferences("river", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

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
        stopRecording(send = false)
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 72, 32, 32)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            view.setPadding(32, 32 + top, 32, 32 + bottom)
            insets
        }
        val title = TextView(this).apply {
            text = "River"
            textSize = 34f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Direct Hermes voice bridge. Audio is sent to River/Hermes for transcription and handling."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        endpointInput = EditText(this).apply {
            hint = "Hermes endpoint URL"
            setSingleLine(true)
            setText(prefs.getString("endpoint", "")?.ifBlank { BuildConfig.RIVER_ENDPOINT } ?: BuildConfig.RIVER_ENDPOINT)
        }
        tokenInput = EditText(this).apply {
            hint = "Bearer token"
            setSingleLine(true)
            setText(prefs.getString("token", "")?.ifBlank { BuildConfig.RIVER_TOKEN } ?: BuildConfig.RIVER_TOKEN)
        }
        val save = Button(this).apply {
            text = "Save endpoint"
            setOnClickListener {
                prefs.edit().putString("endpoint", endpointInput.text.toString().trim()).putString("token", tokenInput.text.toString()).apply()
                log("Saved endpoint settings.")
            }
        }
        askButton = Button(this).apply {
            text = "Record for River"
            textSize = 18f
            setOnClickListener { if (isRecording) stopRecording(send = true) else startVoiceCapture() }
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
        root.addView(askButton, wide())
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
            log("Auto-starting direct Hermes audio capture from ${intent.action ?: "launcher"}.")
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
        val endpoint = currentEndpoint()
        if (endpoint.isBlank()) {
            log("No Hermes endpoint configured.")
            speakAck("River endpoint is not configured yet.")
            return
        }
        requestTransientAudioFocus()
        try {
            val file = File(cacheDir, "river-${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            isRecording = true
            askButton.text = "Stop and send to River"
            log("Recording audio for Hermes STT… auto-sending in 15s if you don’t stop earlier.")
            speakAck("River is recording.")
            handler.postDelayed({ if (isRecording) stopRecording(send = true) }, MAX_RECORDING_MS)
        } catch (e: Exception) {
            log("Recording failed: ${e.message}")
            speakAck("I could not start recording.")
            abandonAudioFocus()
        }
    }

    private fun stopRecording(send: Boolean) {
        val rec = recorder ?: return
        val file = recordingFile
        recorder = null
        recordingFile = null
        isRecording = false
        if (::askButton.isInitialized) askButton.text = "Record for River"
        try {
            rec.stop()
        } catch (_: Exception) {
        } finally {
            rec.release()
        }
        if (send && file != null && file.exists() && file.length() > 0) {
            log("Captured audio: ${file.length()} bytes. Sending to Hermes for transcription.")
            speakAck("Got it — I’ll send the audio to River.")
            postAudioToHermes(file)
        } else {
            log("Recording cancelled.")
            abandonAudioFocus()
        }
    }

    private fun processCapturedText(text: String) {
        log("Captured text: $text")
        speakAck("Got it — I’ll send that to River.")
        postTextToHermes(text)
    }

    private fun requestTransientAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
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

    private fun currentEndpoint(): String = endpointInput.text.toString().trim().ifEmpty { prefs.getString("endpoint", "") ?: BuildConfig.RIVER_ENDPOINT }
    private fun currentToken(): String = tokenInput.text.toString().ifEmpty { prefs.getString("token", "") ?: BuildConfig.RIVER_TOKEN }

    private fun postTextToHermes(text: String) {
        val endpoint = currentEndpoint()
        val token = currentToken()
        if (endpoint.isBlank()) {
            log("No Hermes endpoint configured. Local text test complete.")
            abandonAudioFocus()
            return
        }
        thread {
            try {
                val requestId = UUID.randomUUID().toString()
                val body = "{\"source\":\"river-android\",\"client_request_id\":${json(requestId)},\"text\":${json(text)}}"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                handleAcceptedResponse(conn, token, "Hermes POST")
            } catch (e: Exception) {
                runOnUiThread { log("Hermes POST failed: ${e.message}"); speakAck("I captured it, but could not reach River."); abandonAudioFocus() }
            }
        }
    }

    private fun postAudioToHermes(file: File) {
        val endpoint = currentEndpoint()
        val token = currentToken()
        thread {
            try {
                val boundary = "RiverBoundary${System.currentTimeMillis()}"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                DataOutputStream(conn.outputStream).use { out ->
                    fun field(name: String, value: String) {
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        out.writeBytes(value)
                        out.writeBytes("\r\n")
                    }
                    field("source", "river-android")
                    field("client_request_id", UUID.randomUUID().toString())
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"river.m4a\"\r\n")
                    out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                    file.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }
                handleAcceptedResponse(conn, token, "Hermes audio POST")
            } catch (e: Exception) {
                runOnUiThread { log("Hermes audio POST failed: ${e.message}"); speakAck("I recorded it, but could not reach River."); abandonAudioFocus() }
            } finally {
                file.delete()
            }
        }
    }

    private fun handleAcceptedResponse(conn: HttpURLConnection, token: String, label: String) {
        val code = conn.responseCode
        val response = readResponse(conn, code)
        runOnUiThread { log("$label $code ${response.take(280)}") }
        if (code in 200..299) {
            val json = JSONObject(response)
            val ack = json.optString("ack", "Sent to River.")
            val statusUrl = json.optString("status_url", "")
            runOnUiThread {
                speakAck(ack)
                abandonAudioFocus()
            }
            if (statusUrl.isNotBlank()) pollHermesResult(statusUrl, token)
        } else {
            runOnUiThread {
                speakAck("River endpoint returned an error.")
                abandonAudioFocus()
            }
        }
    }

    private fun pollHermesResult(statusUrl: String, token: String) {
        thread {
            var delayMs = 1800L
            repeat(75) { attempt ->
                try {
                    Thread.sleep(delayMs)
                    val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 12000
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                    val code = conn.responseCode
                    val response = readResponse(conn, code)
                    if (code !in 200..299) {
                        runOnUiThread { log("Hermes status $code ${response.take(220)}") }
                        return@thread
                    }
                    val json = JSONObject(response)
                    val status = json.optString("status", "unknown")
                    if (attempt == 0 || attempt % 5 == 0) runOnUiThread { log("Hermes run status: $status") }
                    when (status) {
                        "completed" -> {
                            val transcript = json.optString("transcript", "").trim()
                            val reply = json.optString("final_reply", "").trim()
                            runOnUiThread {
                                if (transcript.isNotBlank()) log("Transcript: ${transcript.take(500)}")
                                if (reply.isNotBlank()) {
                                    log("River replied: ${reply.take(1000)}")
                                    speakFinal(reply)
                                } else {
                                    log("River completed without a spoken reply.")
                                }
                            }
                            return@thread
                        }
                        "failed" -> {
                            val error = json.optString("error", "River run failed.")
                            runOnUiThread { log("River failed: ${error.take(500)}"); speakAck("River hit an error.") }
                            return@thread
                        }
                    }
                    if (delayMs < 5000L) delayMs += 400L
                } catch (e: Exception) {
                    runOnUiThread { log("Hermes status failed: ${e.message}") }
                    return@thread
                }
            }
            runOnUiThread { log("River is still working; polling stopped after timeout.") }
        }
    }

    private fun readResponse(conn: HttpURLConnection, code: Int): String =
        (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()

    private fun speakFinal(reply: String) {
        val spoken = reply.replace(Regex("\\s+"), " ").take(420)
        requestTransientAudioFocus()
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "river-final-${System.currentTimeMillis()}")
        window.decorView.postDelayed({ abandonAudioFocus() }, 12000)
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

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("\n${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())}  $msg")
        }
    }

    companion object {
        const val ACTION_ASK = "ee.river.android.ASK"
        const val EXTRA_AUTO_START = "auto_start"
        private const val MAX_RECORDING_MS = 15_000L
    }
}
