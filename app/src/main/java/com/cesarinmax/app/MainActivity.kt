package com.cesarinmax.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var db: FirebaseDatabase
    private var configGist: JSONObject? = null
    private var ssidEsperado = "Cesarín Max"
    private var macRouterEsperado = "PONES-AQUI-TU-MAC-DESPUES"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = FirebaseDatabase.getInstance()

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

    private fun cargarConfigGist() {
        try {
            val url = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/1077573cadcdc04760bc9dc0484ae0753b14650d/cesarin_max_config.json"
            val respuesta = URL(url).readText()
            configGist = JSONObject(respuesta)

            val configRed = configGist?.getJSONObject("config_red")
            ssidEsperado = configRed?.optString("ssid_esperado", "Cesarín Max")!!
            macRouterEsperado = configRed?.optString("mac_router", "")!!

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun verificarRed(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo = wifiManager.connectionInfo

        val ssidActual = wifiInfo.ssid
            .replace("\"", "")
            .replace("<unknown ssid>", "")

        val macActual = wifiInfo.bssid ?: ""

        // Si todavía no tenés la MAC, la app deja pasar (comentá la línea de abajo cuando tengas tu MAC)
        if (macRouterEsperado == "PONES-AQUI-TU-MAC-DESPUES" || macRouterEsperado.isBlank()) {
            return ssidActual.equals(ssidEsperado, ignoreCase = true)
        }

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
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        webView.webChromeClient = android.webkit.WebChromeClient()
    }

    private fun cargarPortal() {
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
