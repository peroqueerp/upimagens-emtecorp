package com.example.upimagens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    // ===== Views =====
    private lateinit var previewView: PreviewView
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutOffline: LinearLayout
    private lateinit var layoutDashboard: LinearLayout
    private lateinit var layoutCameraOverlay: FrameLayout
    private lateinit var layoutQueueBanner: LinearLayout
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var tvQueueCount: TextView
    private lateinit var tvQueueBadge: TextView
    private lateinit var btnCapture: AppCompatButton
    private lateinit var layoutSyncError: LinearLayout
    private lateinit var tvSyncErrorDesc: TextView
    private lateinit var btnDismissSyncError: AppCompatButton
    
    private var errorRingtone: android.media.Ringtone? = null

    // ===== Estado =====
    private lateinit var dbHelper: LogDatabaseHelper
    private var isDashboardOpen = false

    // ===== CameraX =====
    private var imageCapture: ImageCapture? = null

    // ===== Fila de Upload =====
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val pendingUploadsCount = AtomicInteger(0)

    // ===== URL do Google Apps Script =====
    private val appsScriptUrl =
        "https://script.google.com/macros/s/AKfycbw2me8Bj4WeNImqYIXjl5SU-Kp6DP_ZfTf62ygXX7EsQJKdF1AZIRJzgbfy_o73GGM9/exec"

    // ===== Launcher de permissão da câmera =====
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            dbHelper.log("SYSTEM", "Permissão de câmera concedida.")
            startCameraX()
        } else {
            dbHelper.log("ERROR", "Permissão de câmera negada pelo usuário.")
            setStatus("error", "Permissão de câmera necessária para usar o app.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = LogDatabaseHelper(this)
        dbHelper.log("SYSTEM", "Aplicativo iniciado (CameraX)")

        // Referências de Views
        previewView       = findViewById(R.id.previewView)
        webView           = findViewById(R.id.webView)
        progressBar       = findViewById(R.id.progressBar)
        layoutOffline     = findViewById(R.id.layoutOffline)
        layoutDashboard   = findViewById(R.id.layoutDashboard)
        layoutCameraOverlay = findViewById(R.id.layoutCameraOverlay)
        layoutQueueBanner = findViewById(R.id.layoutQueueBanner)
        tvStatusTitle     = findViewById(R.id.tvStatusTitle)
        tvStatusDesc      = findViewById(R.id.tvStatusDesc)
        tvQueueCount      = findViewById(R.id.tvQueueCount)
        tvQueueBadge      = findViewById(R.id.tvQueueBadge)
        btnCapture        = findViewById(R.id.btnCapture)
        layoutSyncError   = findViewById(R.id.layoutSyncError)
        tvSyncErrorDesc   = findViewById(R.id.tvSyncErrorDesc)
        btnDismissSyncError = findViewById(R.id.btnDismissSyncError)

        setupWebView()
        setupListeners()
        setupOnBackPressed()
        checkCameraPermissionAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        uploadExecutor.shutdown()
    }

    // =========================================================
    //  CÂMERA
    // =========================================================

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraX()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                dbHelper.log("SYSTEM", "CameraX iniciado com sucesso.")
                setStatus("ready", "Toque no botão para capturar e enviar")
            } catch (e: Exception) {
                dbHelper.log("ERROR", "Falha ao iniciar câmera: ${e.message}")
                setStatus("error", "Não foi possível iniciar a câmera.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: run {
            dbHelper.log("ERROR", "ImageCapture não inicializado.")
            return
        }

        if (!isNetworkAvailable()) {
            dbHelper.log("ERROR", "Sem rede ao tentar capturar.")
            setStatus("error", "Sem conexão. Conecte-se à internet e tente novamente.")
            showOfflineLayout()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timeStamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Feedback tátil imediato ao toque
        btnCapture.alpha = 0.6f
        btnCapture.postDelayed({ btnCapture.alpha = 1.0f }, 150)

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    if (uri != null) {
                        dbHelper.log("UPLOAD", "Foto capturada: $uri")
                        enqueueUpload(uri)
                    } else {
                        dbHelper.log("ERROR", "URI da foto retornou nula após captura.")
                        setStatus("error", "Erro ao salvar foto. Tente novamente.")
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    dbHelper.log("ERROR", "Falha na captura CameraX: ${exc.message}")
                    setStatus("error", "Erro na captura: ${exc.message}")
                }
            }
        )
    }

    // =========================================================
    //  FILA DE UPLOAD
    // =========================================================

    private fun enqueueUpload(uri: Uri) {
        val count = pendingUploadsCount.incrementAndGet()
        dbHelper.log("UPLOAD", "Foto adicionada à fila. Total: $count")
        updateQueueUI()

        uploadExecutor.submit {
            try {
                val pending = pendingUploadsCount.get()
                runOnUiThread {
                    setStatus(
                        "uploading",
                        if (pending > 1) "Enviando... ($pending na fila)" else "Enviando foto..."
                    )
                }

                val bytes: ByteArray = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Não foi possível ler a imagem: $uri")

                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val filename = "IMG_$ts.jpg"

                val payload = org.json.JSONObject().apply {
                    put("base64", base64)
                    put("mimeType", "image/jpeg")
                    put("filename", filename)
                }.toString()

                dbHelper.log("UPLOAD", "Transmitindo: $filename")

                var conn = java.net.URL(appsScriptUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                var code = conn.responseCode
                dbHelper.log("SYSTEM", "HTTP inicial: $code")

                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = java.net.URL(location).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    code = conn.responseCode
                    dbHelper.log("SYSTEM", "HTTP pós-redirect: $code")
                }

                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = org.json.JSONObject(body)
                    if (json.optString("status") == "success") {
                        dbHelper.log("UPLOAD", "Upload OK: ${json.optString("fileId")}")
                    } else {
                        val errMsg = "Apps Script Error: ${json.optString("message")}"
                        dbHelper.log("ERROR", errMsg)
                        showRedErrorScreen(errMsg)
                        reportErrorToBackend(errMsg)
                    }
                } else {
                    val errMsg = "HTTP Error $code para $filename"
                    dbHelper.log("ERROR", errMsg)
                    showRedErrorScreen(errMsg)
                    reportErrorToBackend(errMsg)
                }

            } catch (e: Exception) {
                val errMsg = "Exceção no upload: ${e.message}"
                dbHelper.log("ERROR", errMsg)
                showRedErrorScreen(errMsg)
                reportErrorToBackend(errMsg)
            } finally {
                val remaining = pendingUploadsCount.decrementAndGet()
                dbHelper.log("SYSTEM", "Upload concluído. Restando: $remaining")
                runOnUiThread {
                    if (remaining == 0) {
                        setStatus("success", "Todas as fotos enviadas!")
                        updateQueueUI()
                    } else {
                        setStatus("uploading", "Enviando... ($remaining restante(s))")
                        updateQueueUI()
                    }
                }
            }
        }
    }

    // =========================================================
    //  UI HELPERS
    // =========================================================

    private fun setStatus(state: String, message: String) {
        runOnUiThread {
            when (state) {
                "ready" -> {
                    tvStatusTitle.setTextColor(0xFFFFFFFF.toInt())
                    tvStatusTitle.text = "Pronto para Capturar"
                    tvStatusDesc.text = message
                }
                "uploading" -> {
                    tvStatusTitle.setTextColor(0xFF818CF8.toInt())
                    tvStatusTitle.text = "Enviando Foto..."
                    tvStatusDesc.text = message
                }
                "success" -> {
                    tvStatusTitle.setTextColor(0xFF10B981.toInt())
                    tvStatusTitle.text = "Enviado! ✓"
                    tvStatusDesc.text = message
                    // Volta ao estado normal após 3s
                    tvStatusTitle.postDelayed({
                        if (pendingUploadsCount.get() == 0) {
                            setStatus("ready", "Toque no botão para capturar e enviar")
                        }
                    }, 3000)
                }
                "error" -> {
                    tvStatusTitle.setTextColor(0xFFEF4444.toInt())
                    tvStatusTitle.text = "Erro"
                    tvStatusDesc.text = message
                }
            }
        }
    }

    private fun updateQueueUI() {
        runOnUiThread {
            val pending = pendingUploadsCount.get()
            if (pending > 0) {
                tvQueueBadge.text = "$pending"
                tvQueueBadge.visibility = View.VISIBLE
                layoutQueueBanner.visibility = View.VISIBLE
                tvQueueCount.text = if (pending == 1) "1 foto na fila de upload" else "$pending fotos na fila de upload"
            } else {
                tvQueueBadge.visibility = View.GONE
                layoutQueueBanner.visibility = View.GONE
            }
        }
    }

    private fun showOfflineLayout() {
        runOnUiThread {
            layoutOffline.visibility = View.VISIBLE
            layoutCameraOverlay.visibility = View.GONE
        }
    }

    private fun hideOfflineLayout() {
        runOnUiThread {
            layoutOffline.visibility = View.GONE
            layoutCameraOverlay.visibility = View.VISIBLE
        }
    }

    // =========================================================
    //  DASHBOARD (WebView)
    // =========================================================

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
    }

    private fun openDashboard() {
        isDashboardOpen = true
        dbHelper.log("SYSTEM", "Abrindo painel de logs.")
        layoutDashboard.visibility = View.VISIBLE
        webView.loadUrl("file:///android_asset/dashboard.html")
    }

    private fun closeDashboard() {
        isDashboardOpen = false
        dbHelper.log("SYSTEM", "Fechando painel de logs.")
        layoutDashboard.visibility = View.GONE
    }

    // =========================================================
    //  LISTENERS & BACK PRESS
    // =========================================================

    private fun setupListeners() {
        // Botão shutter — captura imediata
        btnCapture.setOnClickListener { capturePhoto() }

        // Abrir dashboard
        findViewById<androidx.appcompat.widget.AppCompatImageView?>(R.id.btnDashboard)
            ?: findViewById<android.widget.ImageView>(R.id.btnDashboard)
        val btnDash = findViewById<android.widget.ImageView>(R.id.btnDashboard)
        btnDash.setOnClickListener { openDashboard() }

        // Fechar dashboard
        val btnClose = findViewById<android.widget.ImageView>(R.id.btnCloseDashboard)
        btnClose.setOnClickListener { closeDashboard() }

        // Retry (offline)
        val btnRetry = findViewById<AppCompatButton>(R.id.btnRetry)
        btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                hideOfflineLayout()
            } else {
                dbHelper.log("NETWORK", "Tentativa de reconexão falhou.")
            }
        }

        // Abrir logs (offline)
        val btnOpenLogs = findViewById<AppCompatButton>(R.id.btnOpenLogs)
        btnOpenLogs.setOnClickListener { openDashboard() }

        btnDismissSyncError.setOnClickListener { hideRedErrorScreen() }
    }

    private fun showRedErrorScreen(message: String) {
        runOnUiThread {
            tvSyncErrorDesc.text = message
            layoutSyncError.visibility = View.VISIBLE
            
            // Tocar som de alerta longo e alto (Alarme)
            try {
                if (errorRingtone == null) {
                    val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    errorRingtone = android.media.RingtoneManager.getRingtone(applicationContext, uri)
                }
                errorRingtone?.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideRedErrorScreen() {
        runOnUiThread {
            layoutSyncError.visibility = View.GONE
            
            // Parar o alarme quando fechar a tela
            try {
                errorRingtone?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun reportErrorToBackend(errorMessage: String) {
        uploadExecutor.submit {
            try {
                val payload = org.json.JSONObject().apply {
                    put("action", "log_error")
                    put("errorMessage", errorMessage)
                }.toString()

                var conn = java.net.URL(appsScriptUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                var code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = java.net.URL(location).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    code = conn.responseCode
                }
                dbHelper.log("SYSTEM", "Reporte de erro finalizado com código $code")
            } catch (e: Exception) {
                dbHelper.log("ERROR", "Falha ao reportar erro ao backend: ${e.message}")
            }
        }
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isDashboardOpen -> closeDashboard()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // =========================================================
    //  REDE
    // =========================================================

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // =========================================================
    //  JAVASCRIPT BRIDGE (Dashboard)
    // =========================================================

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun getLogs(limit: Int): String = dbHelper.getLogsJson(limit)

        @JavascriptInterface
        fun getStats(): String = dbHelper.getStatsJson()

        @JavascriptInterface
        fun clearLogs() {
            dbHelper.clearLogs()
            dbHelper.log("SYSTEM", "Logs limpos pelo usuário.")
        }

        @JavascriptInterface
        fun closeDashboard() {
            runOnUiThread { this@MainActivity.closeDashboard() }
        }

        @JavascriptInterface
        fun getPendingUploads(): Int = pendingUploadsCount.get()
    }
}
