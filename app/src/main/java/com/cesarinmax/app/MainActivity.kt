package com.cesarinmax.app

import android.Manifest
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

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
                
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    handler?.proceed()
                }

                // ✅ Versión NUEVA — Android 7+
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        mostrarError(view)
                    }
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }

            loadUrl("https://172.16.1.1/login.html")
        }

        setContentView(
            LinearLayout(this).apply {
                addView(webView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ))
            }
        )
    }

    private fun mostrarError(view: WebView?) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    *{margin:0;padding:0;box-sizing:border-box;font-family:Arial,sans-serif;}
                    body{background:linear-gradient(180deg,#000520,#000);color:#fff;text-align:center;padding:60px 20px;min-height:100vh;}
                    h2{color:#ffcc00;font-size:24px;margin-bottom:15px;}
                    p{font-size:17px;line-height:1.6;color:#ddd;margin-bottom:10px;}
                    .consejo{color:#888;margin-top:30px;font-size:15px;}
                    .boton{margin-top:35px;padding:14px 40px;background:linear-gradient(90deg,#0066ff,#00ccff);color:#fff;border:none;border-radius:10px;font-size:18px;font-weight:bold;cursor:pointer;}
                </style>
            </head>
            <body>
                <h2>⚠️ Portal no disponible</h2>
                <p>No se pudo conectar al portal de Ciber Cesarín.</p>
                <p class="consejo">Verifica que estás conectado al WiFi correcto<br>y vuelve a abrir la aplicación.</p>
                <button class="boton" onclick="location.reload()">🔄 Reintentar</button>
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

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
