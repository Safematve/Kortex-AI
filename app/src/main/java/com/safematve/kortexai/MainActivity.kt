package com.safematve.kortexai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Kortex AI — нативная оболочка.
 *
 * JS-мост (доступен только на локальной странице):
 *   window.KortexNative.startListening() / stopListening()
 *   window.KortexNative.speak(text) / stopSpeaking()
 *   window.KortexNative.isVoiceInputSupported() / isTtsReady()
 *   window.KortexNative.saveTextFile(name, text) / saveBase64File(name, mime, b64)
 *   window.KortexNative.openInBrowser(url) / toast(msg)
 *
 * Колбэки Native -> JS (объект window.KortexVoice):
 *   onListeningStart(), onListeningStop(), onPartial(text), onResult(text, true),
 *   onRms(level 0..1), onError(message), onSpeakStart(), onSpeakEnd()
 *   window.KortexDownloads.onSaved(name)
 *
 * HTTP API локального сервера:
 *   GET  /                 -> assets/index.html
 *   GET  /__ping           -> "pong"
 *   POST /__upload?name=x  -> raw-body файла, ответ {"ok":true,"url":"/uploads/...","name":...,"size":...}
 *   GET  /uploads/<name>   -> загруженный файл
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "KortexAI"
        // Фиксированный порт критичен: origin стабилен => localStorage (история чатов) живёт между запусками.
        private val PORT_CANDIDATES = intArrayOf(8123, 8124, 8125, 8126)
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var backButton: TextView

    private val server = LocalWebServer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val popupDialogs = mutableListOf<Dialog>()

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val speakRemaining = AtomicInteger(0)

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var wantListening = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMicAction: (() -> Unit)? = null
    private var pendingMicDeny: (() -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val onGranted = pendingMicAction
            val onDenied = pendingMicDeny
            pendingMicAction = null
            pendingMicDeny = null
            if (granted) onGranted?.invoke()
            else {
                onDenied?.invoke()
                jsVoice("onError", JSONObject.quote("Разрешение на микрофон не выдано"))
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureWebView()
        initTts()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    !isLocalUrl(webView.url) -> returnHome()
                    else -> moveTaskToBack(true)
                }
            }
        })

        // Сервер стартуем в фоне, страницу грузим только когда порт известен.
        thread(name = "KortexHttpStart") {
            try {
                server.start()
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    val restored = savedInstanceState?.let { webView.restoreState(it) }
                    if (restored == null || webView.url == null) webView.loadUrl(server.baseUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local server failed", e)
                toast("Не удалось запустить локальный сервер")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        tts?.stop()
        speakRemaining.set(0)
        if (wantListening) stopRecognition()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        server.stop()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        popupDialogs.toList().forEach { it.dismiss() }
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.kortex_bg))
        }

        webView = WebView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        root.addView(webView, FrameLayout.LayoutParams(match, match))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(Color.parseColor("#ECECEC"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        root.addView(progressBar, FrameLayout.LayoutParams(match, dp(2), Gravity.TOP))

        backButton = TextView(this).apply {
            text = "←  Kortex AI"
            setTextColor(Color.parseColor("#ECECEC"))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_back_button)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            contentDescription = "Вернуться в Kortex AI"
            setOnClickListener { returnHome() }
            // Долгое нажатие — аварийный выход в системный браузер (если сайт блокирует WebView).
            setOnLongClickListener { openInBrowser(webView.url ?: server.baseUrl); true }
        }
        root.addView(backButton, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        setContentView(root)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true   // авто-попап авторизации Puter.js
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = false                        // всё через http://127.0.0.1
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.textZoom = 100
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Убираем маркеры WebView из UA — часть сайтов (вход Google) иначе отказывает.
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true) // тёмный вид внешних сайтов
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)    // сессии chatgpt/claude и т.д.
        }

        webView.addJavascriptInterface(KortexBridge(), "KortexNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase(Locale.US) ?: return false
                return when (scheme) {
                    "http", "https", "blob", "data", "about" -> false // грузим внутри приложения
                    else -> { openInBrowser(request.url.toString()); true }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                url?.let { updateBackButton(it) }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateBackButton(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (audio.isEmpty()) { request.deny(); return }
                runOnUiThread {
                    ensureMicPermission(
                        onGranted = { request.grant(audio.toTypedArray()) },
                        onDenied = { request.deny() }
                    )
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = params?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = showPopupWindow(resultMsg)
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    /** Полноценное popup-окно (Puter.js sign-in): сохраняем связь window.opener через WebViewTransport. */
    private fun showPopupWindow(resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(this)
        popup.settings.javaScriptEnabled = true
        popup.settings.domStorageEnabled = true
        popup.settings.userAgentString = webView.settings.userAgentString
        popup.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        val dialog = Dialog(this)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#171717")))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        popup.webViewClient = WebViewClient()
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            popupDialogs.remove(dialog)
            popup.destroy()
        }
        dialog.setContentView(popup)
        dialog.show()
        popupDialogs.add(dialog)

        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    /** Скачивание: blob: (песочница) читаем через FileReader, http(s) — через DownloadManager. */
    private fun handleDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val mime = mimeType ?: "application/octet-stream"
        val fileName = URLUtil.guessFileName(url, contentDisposition, mime)
        when {
            url.startsWith("blob:") -> {
                val js = """
                    (function() {
                        try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', ${JSONObject.quote(url)}, true);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = String(reader.result).split(',')[1] || '';
                                    window.KortexNative.saveBase64File(
                                        ${JSONObject.quote(fileName)}, ${JSONObject.quote(mime)}, base64);
                                };
                                reader.readAsDataURL(xhr.response);
                            };
                            xhr.send();
                        } catch (e) { window.KortexNative.toast('Не удалось скачать файл'); }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mime)
                        .addRequestHeader("User-Agent", userAgent)
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setTitle(fileName)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    toast("Загрузка: $fileName")
                } catch (e: Exception) {
                    openInBrowser(url)
                }
            }
            else -> toast("Неподдерживаемый тип загрузки")
        }
    }

    // ------------------------------------------------------------------ JS-мост

    private inner class KortexBridge {
        @JavascriptInterface
        fun isVoiceInputSupported(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun isTtsReady(): Boolean = ttsReady

        @JavascriptInterface
        fun startListening() {
            mainHandler.post {
                ensureMicPermission(
                    onGranted = { startRecognition() },
                    onDenied = { jsVoice("onError", JSONObject.quote("Нет доступа к микрофону")) }
                )
            }
        }

        @JavascriptInterface
        fun stopListening() { mainHandler.post { stopRecognition() } }

        @JavascriptInterface
        fun speak(text: String) { mainHandler.post { speakOut(text) } }

        @JavascriptInterface
        fun stopSpeaking() {
            mainHandler.post {
                tts?.stop()
                speakRemaining.set(0)
                jsVoice("onSpeakEnd")
            }
        }

        @JavascriptInterface
        fun saveTextFile(name: String, text: String) {
            saveToDownloads(name, "text/html", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }

        @JavascriptInterface
        fun saveBase64File(name: String, mime: String, base64: String) {
            saveToDownloads(name, mime, base64)
        }

        @JavascriptInterface
        fun openInBrowser(url: String) { this@MainActivity.openInBrowser(url) }

        @JavascriptInterface
        fun toast(message: String) { this@MainActivity.toast(message) }
    }

    /** Сохранение в публичные «Загрузки»: MediaStore на API 29+, каталог приложения на старых API. */
    private fun saveToDownloads(rawName: String, mime: String, base64: String) {
        try {
            val name = sanitizeFileName(rawName)
            val data = Base64.decode(base64.substringAfterLast(','), Base64.DEFAULT)
            val location: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore вернул null")
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                location = "Загрузки/$name"
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = uniqueFile(dir, name)
                FileOutputStream(file).use { it.write(data) }
                location = file.absolutePath
            }
            toast("Сохранено: $location")
            mainHandler.post {
                webView.evaluateJavascript(
                    "window.KortexDownloads && window.KortexDownloads.onSaved && " +
                        "window.KortexDownloads.onSaved(${JSONObject.quote(name)});", null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads", e)
            toast("Ошибка сохранения файла")
        }
    }

    // ------------------------------------------------------------------ TTS (озвучка)

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val result = t.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.getDefault())
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == "kortex-0") jsVoice("onSpeakStart")
                    }
                    override fun onDone(utteranceId: String?) {
                        if (speakRemaining.decrementAndGet() <= 0) jsVoice("onSpeakEnd")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speakRemaining.set(0)
                        jsVoice("onSpeakEnd")
                    }
                })
                ttsReady = true
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    private fun speakOut(text: String) {
        val t = tts
        if (t == null || !ttsReady) {
            jsVoice("onError", JSONObject.quote("Синтез речи недоступен"))
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (wantListening) stopRecognition() // не слушаем собственный голос
        t.stop()
        val chunks = chunkText(clean, 3500)  // лимит движка ~4000 символов
        speakRemaining.set(chunks.size)
        chunks.forEachIndexed { index, chunk ->
            t.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "kortex-$index")
        }
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val parts = text.split(Regex("(?<=[.!?…\\n])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (part in parts) {
            if (current.length + part.length + 1 > maxLen && current.isNotEmpty()) {
                chunks.add(current.toString()); current.setLength(0)
            }
            if (part.length > maxLen) {
                var start = 0
                while (start < part.length) {
                    val end = minOf(start + maxLen, part.length)
                    chunks.add(part.substring(start, end)); start = end
                }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    // ------------------------------------------------------------------ Распознавание речи (ru-RU)

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jsVoice("onError", JSONObject.quote("Служба распознавания речи не найдена на устройстве"))
            return
        }
        if (speakRemaining.getAndSet(0) > 0) { tts?.stop(); jsVoice("onSpeakEnd") }
        wantListening = true
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        try {
            recognizer?.startListening(buildRecognizerIntent())
        } catch (e: Exception) {
            wantListening = false
            jsVoice("onError", JSONObject.quote("Не удалось запустить микрофон"))
        }
    }

    private fun stopRecognition() {
        wantListening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        jsVoice("onListeningStop")
    }

    /** В режиме ассистента держим микрофон «открытым»: авто-перезапуск после пауз/тишины. */
    private fun scheduleRestart() {
        mainHandler.postDelayed({
            if (wantListening) {
                try { recognizer?.startListening(buildRecognizerIntent()) } catch (_: Exception) {}
            }
        }, 300)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { jsVoice("onListeningStart") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) // громкость -> анимация сферы
            jsVoice("onRms", String.format(Locale.US, "%.3f", level))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (!wantListening) return
            val retryable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (retryable) {
                scheduleRestart()
            } else {
                wantListening = false
                jsVoice("onListeningStop")
                jsVoice("onError", JSONObject.quote(recognitionErrorMessage(error)))
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) jsVoice("onResult", "${JSONObject.quote(text)}, true")
            if (wantListening) scheduleRestart()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) jsVoice("onPartial", JSONObject.quote(text))
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun recognitionErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Проблема с сетью распознавания"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания"
        else -> "Не удалось распознать речь"
    }

    private fun ensureMicPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingMicAction = onGranted
            pendingMicDeny = onDenied
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun jsVoice(event: String, args: String = "") {
        val code = "window.KortexVoice && window.KortexVoice.$event && window.KortexVoice.$event($args);"
        mainHandler.post {
            if (!isFinishing && !isDestroyed) webView.evaluateJavascript(code, null)
        }
    }

    private fun isLocalUrl(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return true
        return host == "127.0.0.1" || host == "localhost"
    }

    private fun updateBackButton(url: String?) {
        backButton.visibility = if (isLocalUrl(url)) View.GONE else View.VISIBLE
    }

    private fun returnHome() { webView.loadUrl(server.baseUrl) }

    private fun openInBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: ActivityNotFoundException) { toast("Браузер не найден") }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifBlank { "kortex-file" }.take(120)

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (candidate.exists()) {
            val next = if (ext.isNotEmpty()) "$base ($index).$ext" else "$base ($index)"
            candidate = File(dir, next)
            index++
        }
        return candidate
    }

    // ------------------------------------------------------------------ Локальный HTTP-сервер

    private inner class LocalWebServer {
        private var serverSocket: ServerSocket? = null
        private val workers = Executors.newCachedThreadPool { r ->
            Thread(r, "KortexHttpWorker").apply { isDaemon = true }
        }
        @Volatile private var running = false
        var port: Int = 0
            private set
        val baseUrl: String get() = "http://127.0.0.1:$port/"

        fun start() {
            var socket: ServerSocket? = null
            for (candidate in PORT_CANDIDATES) {
                socket = try {
                    ServerSocket(candidate, 32, InetAddress.getByName("127.0.0.1"))
                } catch (e: Exception) { null }
                if (socket != null) break
            }
            if (socket == null) socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            port = socket.localPort
            running = true
            Thread({ acceptLoop() }, "KortexHttpAccept").apply { isDaemon = true }.start()
            Log.i(TAG, "Local server started: $baseUrl")
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
            workers.shutdownNow()
        }

        private fun acceptLoop() {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    workers.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept: ${e.message}")
                }
            }
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.use { s ->
                    s.soTimeout = 20_000
                    val input = BufferedInputStream(s.getInputStream())
                    val output = BufferedOutputStream(s.getOutputStream())

                    val requestLine = readHeaderLine(input) ?: return
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) {
                        respond(output, 400, "text/plain", "Bad request".toByteArray()); return
                    }
                    val method = parts[0].uppercase(Locale.US)
                    val target = parts[1]

                    var contentLength = 0
                    while (true) {
                        val line = readHeaderLine(input) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                            contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                        }
                    }

                    val qIdx = target.indexOf('?')
                    val rawPath = if (qIdx >= 0) target.substring(0, qIdx) else target
                    val query = if (qIdx >= 0) target.substring(qIdx + 1) else ""
                    val path = URLDecoder.decode(rawPath, "UTF-8")
                    val headOnly = method == "HEAD"

                    when {
                        method == "POST" && path == "/__upload" ->
                            handleUpload(query, contentLength, input, output)
                        method != "GET" && !headOnly ->
                            respond(output, 405, "text/plain", "Method not allowed".toByteArray())
                        path == "/" || path == "/index.html" ->
                            serveAsset("index.html", output, headOnly)
                        path == "/__ping" ->
                            respond(output, 200, "text/plain; charset=utf-8", "pong".toByteArray(), headOnly)
                        path == "/favicon.ico" ->
                            respond(output, 204, "image/x-icon", ByteArray(0), headOnly)
                        path.startsWith("/uploads/") ->
                            serveUpload(path.removePrefix("/uploads/"), output, headOnly)
                        else ->
                            serveAsset(path.trimStart('/'), output, headOnly)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP client: ${e.message}")
            }
        }

        private fun serveAsset(assetPath: String, output: OutputStream, headOnly: Boolean) {
            try {
                val bytes = assets.open(assetPath).use { it.readBytes() }
                respond(output, 200, mimeFor(assetPath), bytes, headOnly)
            } catch (e: Exception) {
                respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray(Charsets.UTF_8), headOnly)
            }
        }

        private fun serveUpload(rawName: String, output: OutputStream, headOnly: Boolean) {
            val name = sanitizeFileName(rawName) // защита от обхода каталога
            val file = File(File(cacheDir, "kortex-uploads"), name)
            if (!file.isFile) {
                respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray(Charsets.UTF_8), headOnly)
                return
            }
            respond(output, 200, mimeFor(name), file.readBytes(), headOnly)
        }

        private fun handleUpload(query: String, contentLength: Int, input: InputStream, output: OutputStream) {
            if (contentLength <= 0 || contentLength > 64 * 1024 * 1024) {
                respond(output, 413, "application/json", """{"ok":false,"error":"empty or too large"}""".toByteArray())
                return
            }
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            val params = parseQuery(query)
            val name = sanitizeFileName(params["name"] ?: "upload.bin")
            val dir = File(cacheDir, "kortex-uploads").apply { mkdirs() }
            val file = uniqueFile(dir, name)
            FileOutputStream(file).use { it.write(body, 0, read) }
            val json = JSONObject()
                .put("ok", true)
                .put("name", file.name)
                .put("size", read)
                .put("url", "/uploads/" + Uri.encode(file.name))
            respond(output, 200, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
        }

        private fun respond(output: OutputStream, code: Int, mime: String, body: ByteArray, headOnly: Boolean = false) {
            val status = when (code) {
                200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
                404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"
                else -> "Internal Server Error"
            }
            val headers = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) output.write(body)
            output.flush()
        }

        private fun readHeaderLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 8192) break
            }
            return buf.toString("ISO-8859-1")
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate {
                val idx = it.indexOf('=')
                if (idx >= 0) URLDecoder.decode(it.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                else URLDecoder.decode(it, "UTF-8") to ""
            }

        private fun mimeFor(name: String): String =
            when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                "html", "htm" -> "text/html; charset=utf-8"
                "js", "mjs" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "txt", "md" -> "text/plain; charset=utf-8"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "ico" -> "image/x-icon"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "pdf" -> "application/pdf"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                else -> "application/octet-stream"
            }
    }
}
