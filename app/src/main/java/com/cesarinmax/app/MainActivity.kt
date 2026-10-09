package com.cesarinmax.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val alcanceCorutina = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // === VARIABLE PARA ABRIR ARCHIVOS/FOTOS ===
    private var permisoArchivoCallback: ((Uri?) -> Unit)? = null
    private var archivoCallback: ((Array<Uri>?) -> Unit)? = null

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    
    // === PEDIR PERMISO DE MICRÓFONO ===
    private val permisoMicrofono = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    
    // === SELECCIONAR ARCHIVOS/FOTOS ===
    private val seleccionarArchivo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        permisoArchivoCallback?.invoke(uri)
        permisoArchivoCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Pedir permisos al iniciar
        permisoCamara.launch(Manifest.permission.CAMERA)
        permisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)

        // 🔒 VERIFICACIÓN — SI NO ESTÁ → TU PANTALLA SOLAMENTE
        if (!verificarRed()) {
            setContentView(crearPantallaRestringida())
            return
        }

        // ✅ SI ESTÁ → ENTRA DIRECTO AL PORTAL
        configurarWebView()
    }

    // ==============================================
    // ✅ DETECCIÓN DE IP — LA QUE SÍ FUNCIONA
    // ==============================================
    private fun verificarRed(): Boolean {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        if (!wifi.isWifiEnabled) return false

        val ipInt = wifi.connectionInfo.ipAddress
        if (ipInt == 0) return false

        val ipStr = String.format(
            Locale.getDefault(),
            "%d.%d.%d.%d",
            ipInt and 0xFF,
            ipInt shr 8 and 0xFF,
            ipInt shr 16 and 0xFF,
            ipInt shr 24 and 0xFF
        )

        return ipStr.startsWith("172.16.1.")
    }

    // ==============================================
    // ❌ TU PANTALLA — ÚNICA QUE SE MUESTRA
    // ==============================================
    private fun crearPantallaRestringida(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#000000"))
            setPadding(40, 80, 40, 50)
            gravity = android.view.Gravity.CENTER_HORIZONTAL

            fun texto(texto: String, tam: Float, color: String, negrita: Boolean = false, margen: Int = 10) {
                val tv = android.widget.TextView(context).apply {
                    text = texto
                    textSize = tam
                    setTextColor(android.graphics.Color.parseColor(color))
                    if (negrita) setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, margen, 0, margen)
                    gravity = android.view.Gravity.CENTER
                }
                addView(tv)
            }

            texto("🔒", 48f, "#FFCC00", margen = 0)
            texto("ACCESO\nRESTRINGIDO", 36f, "#FFCC00", true, 15)
            texto("CONÉCTATE AL WIFI", 30f, "#FFFFFF", margen = 40)
            texto("CESARINMAX", 52f, "#FFCC00", true, 5)
            texto("DE PAOYHAN", 42f, "#FFFFFF", true, 5)
            texto("¡DISFRUTA DE TODO! ₲", 32f, "#FFCC00", margen = 50)
            texto("CONÉCTATE A LA RED OFICIAL\nVUELVE A ABRIR LA APLICACIÓN", 18f, "#888888", margen = 30)

            val btn = android.widget.Button(context).apply {
                text = "🔄 VOLVER A INTENTAR"
                setBackgroundColor(android.graphics.Color.parseColor("#FFCC00"))
                setTextColor(android.graphics.Color.parseColor("#000000"))
                textSize = 18f
                setPadding(40, 15, 40, 15)
                setOnClickListener { recreate() }
            }
            addView(btn)
        }
    }

    // ==============================================
    // 🌐 WEBVIEW ARREGLADO — GALERÍA + MICRÓFONO
    // ==============================================
    private fun configurarWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true // ✅ IMPORTANTE para acceder a galería
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            setDownloadListener { url, _, _, _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                // ✅ PERMISOS DE MICRÓFONO Y CÁMARA
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }

                // ✅ ABRIR SELECCIONADOR DE ARCHIVOS/FOTOS
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    archivoCallback = { uris ->
                        filePathCallback?.onReceiveValue(uris)
                        archivoCallback = null
                    }
                    seleccionarArchivo.launch("image/*")
                    return true
                }
            }

            loadUrl("http://172.16.1.1/login.html")
        }

        setContentView(
            LinearLayout(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#000520"))
                addView(webView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ))
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        alcanceCorutina.cancel()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
