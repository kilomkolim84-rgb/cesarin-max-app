package com.cesarinmax.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiInfo
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
    private var yaMostroError = false
    private val alcanceCorutina = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permisoCamara.launch(Manifest.permission.CAMERA)

        // 🔒 VERIFICACIÓN — USANDO EL MÉTODO QUE SÍ FUNCIONA
        if (!verificarRed()) {
            setContentView(crearPantallaRestringida())
            return
        }

        // ✅ SI ESTÁ BIEN → CARGA NORMAL
        configurarWebView()
    }

    // ==============================================
    // ✅ FUNCIÓN DE IP — EXACTAMENTE COMO LA QUE SÍ FUNCIONA
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
    // ❌ TU PANTALLA DE ACCESO RESTRINGIDO
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
    // 🌐 TU WEBVIEW — IGUAL QUE LA TENÍAS
    // ==============================================
    private fun configurarWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            setDownloadListener { url, _, _, _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    yaMostroError = false
                    alcanceCorutina.launch {
                        delay(8000)
                        if (!yaMostroError) {
                            yaMostroError = true
                            mostrarMensaje(view)
                        }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !yaMostroError) {
                        yaMostroError = true
                        alcanceCorutina.coroutineContext.cancelChildren()
                        mostrarMensaje(view)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    alcanceCorutina.coroutineContext.cancelChildren()
                    view?.evaluateJavascript("""
                        (function(){
                            const t = document.body.innerText.toLowerCase();
                            return (t.includes("404") || t.includes("not found")) ? "404" : "ok";
                        })()
                    """) { res ->
                        if (res == "\"404\"" && !yaMostroError) {
                            yaMostroError = true
                            mostrarMensaje(view)
                        }
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }

            loadUrl("https://172.16.1.1/login.html")
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

    private fun mostrarMensaje(view: WebView?) {
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        *{margin:0;padding:0;box-sizing:border-box;font-family:Arial,sans-serif;}
        html,body{height:100%;background:linear-gradient(180deg,#000520,#000);color:#fff;}
        body{text-align:center;padding:60px 20px;min-height:100vh;}
        .icono{font-size:70px;margin-bottom:20px;}
        h1{color:#ffcc00;font-size:26px;margin-bottom:25px;}
        p{font-size:18px;line-height:1.7;color:#ddd;max-width:400px;margin:0 auto 15px;}
        .consejo{color:#888;margin-top:35px;font-size:15px;}
        .boton{margin-top:40px;padding:15px 45px;background:linear-gradient(90deg,#ffcc00,#ff9900);color:#000;border:none;border-radius:12px;font-size:19px;font-weight:bold;cursor:pointer;}
    </style>
</head>
<body>
    <div class="icono">📶</div>
    <h1>Fuera de cobertura</h1>
    <p>No estás conectado al WiFi de Ciber Cesarín.</p>
    <p class="consejo">Conéctate al WiFi del servicio<br>y vuelve a intentar.</p>
    <button class="boton" onclick="location.reload()">🔄 Volver a intentar</button>
</body>
</html>
        """.trimIndent()

        view?.loadDataWithBaseURL("https://172.16.1.1/", html, "text/html", "UTF-8", null)
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
