package com.cesarinmax.app

import android.Manifest
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

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    yaMostroError = false
                    // ✅ Si en 8 segundos no carga → mostramos el mensaje
                    alcanceCorutina.launch {
                        delay(8000)
                        if (!yaMostroError) {
                            yaMostroError = true
                            mostrarMensaje(view)
                        }
                    }
                }

                // ✅ SIN CONEXIÓN → mensaje inmediato
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !yaMostroError) {
                        yaMostroError = true
                        alcanceCorutina.coroutineContext.cancelChildren()
                        mostrarMensaje(view)
                    }
                }

                // ✅ PÁGINA CARGÓ → cancelamos el temporizador
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

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
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
                setBackgroundColor(android.graphics.Color.parseColor("#000520")) // Fondo fijo
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
        .boton{
            margin-top:40px;
            padding:15px 45px;
            background:linear-gradient(90deg,#ffcc00,#ff9900);
            color:#000;
            border:none;
            border-radius:12px;
            font-size:19px;
            font-weight:bold;
            cursor:pointer;
        }
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

        view?.loadDataWithBaseURL(
            "https://172.16.1.1/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        alcanceCorutina.cancel()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
