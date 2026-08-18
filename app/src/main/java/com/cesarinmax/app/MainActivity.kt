package com.cesarinmax.app

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.zxing.BarcodeFormat
import com.zxing.android.Contents
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var configGist: JSONObject? = null
    private var ssidEsperado = "CESARINMAX"
    private var macRouterEsperado = "PONES-AQUI-TU-MAC-DESPUES"

    private val MODO_PRUEBA_SIEMPRE = true
    private val REQUEST_CAMERA = 1001

    // Datos de configuración cargados del Gist
    private var numeroAdminWhatsapp = "+51974634113"
    private var enlaceGrupoWhatsapp = ""

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

    // ==============================================
    // ✅ PAUSA / CORTE DE AUDIO AL SALIR / MINIMIZAR
    // ==============================================
    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.evaluateJavascript("document.querySelectorAll('audio,video').forEach(el => el.pause());", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.evaluateJavascript("document.querySelectorAll('audio,video').forEach(el => { el.pause(); el.currentTime = 0; });", null)
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    // ==============================================
    // 📋 PERMISOS
    // ==============================================
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

    // ==============================================
    // 🌐 CARGA DE CONFIGURACIÓN DESDE GIST
    // ==============================================
    private fun cargarConfigGist() {
        try {
            val url = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/f688d75e856d4570a0f9056aeac2513dc71d8491/cesarin_max_config.json"
            val respuesta = URL(url).readText()
            configGist = JSONObject(respuesta)

            val configRed = configGist?.getJSONObject("config_red")
            ssidEsperado = configRed?.optString("ssid_esperado", "CESARINMAX")!!
            macRouterEsperado = configRed?.optString("mac_router", "")!!

            // Cargar datos de WhatsApp
            val configApp = configGist?.getJSONObject("app")
            numeroAdminWhatsapp = configApp?.optString("numero_admin_whatsapp", "+51974634113")!!
            enlaceGrupoWhatsapp = configApp?.optString("enlace_grupo_whatsapp", "")!!

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==============================================
    // 📶 VERIFICACIÓN DE RED WiFi
    // ==============================================
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

    // ==============================================
    // 🌐 CONFIGURACIÓN DEL WEBVIEW + PUENTE JS ↔ KOTLIN
    // ==============================================
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(true)
            userAgentString = userAgentString + " CesarinMaxApp/1.0"
        }

        webView.clearCache(true)
        webView.clearHistory()

        // ✅ PUENTE: La web puede llamar a funciones de la app
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

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

    // ==============================================
    // 🎟️ PUENTE — La web llama a estas funciones
    // ==============================================
    inner class WebAppInterface(private val contexto: MainActivity) {
        @JavascriptInterface
        fun premioGanado(jsonPremio: String) {
            val premio = JSONObject(jsonPremio)
            val tipo = premio.optString("tipo", "ninguno")
            val nombre = premio.optString("nombre", "Premio")
            val minutos = premio.optInt("minutos", 0)
            val prefijo = premio.optString("prefijo_codigo", "")

            when (tipo) {
                "internet" -> contexto.crearTicketTiempo(minutos, nombre, prefijo)
                "producto", "recarga" -> contexto.enviarPremioPorWhatsApp(nombre, tipo, prefijo)
                else -> Toast.makeText(contexto, "Ganaste: $nombre", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==============================================
    // 🎟️ CREAR TICKET DE TIEMPO EN FIREBASE
    // ==============================================
    fun crearTicketTiempo(minutos: Int, nombrePremio: String, prefijo: String) {
        val codigo = generarCodigo6Digitos(prefijo)
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val ticket = hashMapOf(
            "codigo" to codigo,
            "tiempo_minutos" to minutos,
            "monto" to 0.0,
            "fecha" to fecha,
            "nombre_premio" to nombrePremio,
            "origen" to "RULETA",
            "leido_por_ticket" to false,
            "leido_por_monedero" to false,
            "leido_por_portal" to false
        )

        FirebaseDatabase.getInstance().reference
            .child("historial")
            .child(codigo)
            .setValue(ticket)
            .addOnSuccessListener {
                mostrarDialogoPremioTiempo(codigo, minutos, nombrePremio)
            }
            .addOnFailureListener { err ->
                Toast.makeText(this, "❌ Error: ${err.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ==============================================
    // 📱 MOSTRAR CÓDIGO Y OPCIONES AL GANAR TIEMPO
    // ==============================================
    private fun mostrarDialogoPremioTiempo(codigo: String, minutos: Int, nombre: String) {
        val minutosStr = if (minutos >= 60) {
            val h = minutos / 60
            val m = minutos % 60
            if (m > 0) "$h h $m m" else "$h horas"
        } else {
            "$minutos min"
        }

        AlertDialog.Builder(this)
            .setTitle("🎉 ¡GANASTE!")
            .setMessage("🏆 $nombre\n\nCódigo: $codigo\n⏱️ Tiempo: $minutosStr\n\nEscaneá el código en el local o compartilo.")
            .setPositiveButton("📤 COMPARTIR CÓDIGO") { _, _ ->
                compartirCodigo(codigo, nombre, minutosStr)
            }
            .setNegativeButton("✅ LISTO", null)
            .show()
    }

    // ==============================================
    // 📲 ENVIAR PREMIO POR WHATSAPP (productos / recargas)
    // ==============================================
    fun enviarPremioPorWhatsApp(nombrePremio: String, tipo: String, prefijo: String) {
        val codigo = generarCodigoCorto(prefijo)
        val tipoStr = when (tipo) {
            "producto" -> "Producto"
            "recarga" -> "Recarga / Diamantes"
            else -> "Premio"
        }

        val mensaje = """
            🎉 ¡GANASTE EN LA RULETA CESARINMAX!
            
            🏆 Premio: $nombrePremio
            📦 Tipo: $tipoStr
            🔢 Código de canje: $codigo
            
            Por favor coordina la entrega.
        """.trimIndent()

        val numero = numeroAdminWhatsapp.replace("+", "").replace(" ", "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$numero&text=${Uri.encode(mensaje)}")
        }

        try {
            startActivity(intent)
            Toast.makeText(this, "✅ Abriendo WhatsApp...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ No tienes WhatsApp instalado", Toast.LENGTH_LONG).show()
        }
    }

    // ==============================================
    // 🔢 GENERADORES DE CÓDIGOS
    // ==============================================
    private fun generarCodigo6Digitos(prefijo: String = ""): String {
        val numeros = (100000..999999).random().toString()
        return if (prefijo.isNotEmpty()) "$prefijo-$numeros" else numeros
    }

    private fun generarCodigoCorto(prefijo: String = ""): String {
        val caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val aleatorio = (1..4).map { caracteres.random() }.joinToString("")
        return if (prefijo.isNotEmpty()) "$prefijo-$aleatorio" else aleatorio
    }

    // ==============================================
    // 📤 COMPARTIR CÓDIGO
    // ==============================================
    private fun compartirCodigo(codigo: String, nombre: String, tiempo: String) {
        val texto = """
            🎟️ Código de canje — CESARINMAX
            🏆 Premio: $nombre
            🔢 Código: $codigo
            ⏱️ Tiempo: $tiempo
            
            ¡Canjéalo en el local!
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        startActivity(Intent.createChooser(intent, "Compartir código"))
    }

    // ==============================================
    // 📂 CARGAR PORTAL WEB
    // ==============================================
    private fun cargarPortal() {
        pedirPermisoCamara()

        val enlacePortal = configGist
            ?.getJSONObject("app")
            ?.optString("enlace_portal_web")
            ?: "https://dulcet-pudding-45f043.netlify.app/?v=1"

        webView.loadUrl(enlacePortal)
        Toast.makeText(this, "✅ Conectado a CESARINMAX", Toast.LENGTH_SHORT).show()
    }

    // ==============================================
    // 🔙 BOTÓN ATRÁS INTELIGENTE
    // ==============================================
    override fun onBackPressed() {
        webView.evaluateJavascript(
            """
            (function(){
                if(typeof cerrarVentanaDesdeApp === 'function'){
                    return cerrarVentanaDesdeApp() ? 'cerrado' : 'no_ventana';
                }
                return 'no_ventana';
            })()
            """.trimIndent()
        ) { resultado ->
            val resp = resultado.removeSurrounding("\"")
            when {
                resp == "cerrado" -> { }
                webView.canGoBack() -> webView.goBack()
                else -> mostrarDialogoSalir()
            }
        }
    }

    private fun mostrarDialogoSalir() {
        AlertDialog.Builder(this)
            .setTitle("🚪 Salir de la aplicación")
            .setMessage("¿Deseas salir de CESARINMAX?")
            .setCancelable(false)
            .setPositiveButton("✅ Aceptar") { _, _ ->
                webView.evaluateJavascript("document.querySelectorAll('audio,video').forEach(el => { el.pause(); el.currentTime = 0; });", null)
                finishAffinity()
            }
            .setNegativeButton("❌ Cancelar") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
