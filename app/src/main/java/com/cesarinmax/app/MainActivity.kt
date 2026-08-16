package com.cesarinmax.app

import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var configGist: JSONObject? = null
    private var ssidEsperado = "CESARINMAX"
    private var macRouterEsperado = "PONES-AQUI-TU-MAC-DESPUES"

    private val MODO_PRUEBA_SIEMPRE = true
    private val REQUEST_CAMERA = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        configurarWebView()

        CoroutineScope(Dispatchers.IO).launch {
            cargarConfigGist()
            withContext(Dispatchers.Main) {
                if (verificarRed()) {
                    cargarPortal()
                } else {
                    mostrarMensajeRedNoAutorizada()
                }
            }
        }
    }

    private fun pedirPermisoCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Cámara habilitada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Sin cámara no se puede escanear QR", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cargarConfigGist() {
        try {
            val url = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/1077573cadcdc04760bc9dc0484ae0753b14650d/cesarin_max_config.json"
            val respuesta = URL(url).readText()
            configGist = JSONObject(respuesta)

            val configRed = configGist?.getJSONObject("config_red")
            ssidEsperado = configRed?.optString("ssid_esperado", "CESARINMAX")!!
            macRouterEsperado = configRed?.optString("mac_router", "")!!

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun verificarRed(): Boolean {
        if (MODO_PRUEBA_SIEMPRE) {
            Toast.makeText(this, "🧪 Modo prueba activado", Toast.LENGTH_SHORT).show()
            return true
        }

        val modoPruebaGist = configGist
            ?.getJSONObject("config_red")
            ?.optBoolean("modo_prueba", false) == true

        if (modoPruebaGist) {
            Toast.makeText(this, "🧪 Modo prueba desde Gist", Toast.LENGTH_SHORT).show()
            return true
        }

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo = wifiManager.connectionInfo

        val ssidActual = wifiInfo.ssid
            .replace("\"", "")
            .replace("<unknown ssid>", "")

        if (macRouterEsperado == "PONES-AQUI-TU-MAC-DESPUES" || macRouterEsperado.isBlank()) {
            return ssidActual.equals(ssidEsperado, ignoreCase = true)
        }

        val macActual = wifiInfo.bssid ?: ""
        return ssidActual.equals(ssidEsperado, ignoreCase = true) &&
               macActual.equals(macRouterEsperado, ignoreCase = true)
    }

    private fun mostrarMensajeRedNoAutorizada() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Red no autorizada")
            .setMessage("Conéctate a la red WiFi \"$ssidEsperado\" para usar esta aplicación.")
            .setCancelable(false)
            .setPositiveButton("Cerrar app") { _, _ -> finish() }
            .show()
    }

    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
            userAgentString = userAgentString + " CesarinMaxApp/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    private fun cargarPortal() {
        pedirPermisoCamara()

        val enlacePortal = configGist
            ?.getJSONObject("app")
            ?.optString("enlace_portal_web")
            ?: "https://tranquil-kheer-9d576f.netlify.app/"

        webView.loadUrl(enlacePortal)
        Toast.makeText(this, "✅ Conectado a Cesarín Max", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
