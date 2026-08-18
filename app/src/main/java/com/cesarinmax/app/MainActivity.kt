package com.cesarinmax.app

import android.app.AlertDialog
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
    private var macRouterEsperado = ""
    private val MODO_PRUEBA_SIEMPRE = true
    private val REQUEST_CAMERA = 1001
    private var numeroAdminWhatsapp = "+51974634113"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configurarWebView()

        CoroutineScope(Dispatchers.IO).launch {
            cargarConfigGist()
            withContext(Dispatchers.Main) {
                if (verificarRed()) cargarPortal()
                else mostrarMensajeRedNoAutorizada()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.evaluateJavascript("document.querySelectorAll('audio,video').forEach(el=>el.pause());", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.evaluateJavascript("document.querySelectorAll('audio,video').forEach(el=>{el.pause();el.currentTime=0;});", null)
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private fun pedirPermisoCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ Cámara habilitada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cargarConfigGist() {
        try {
            val url = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/f688d75e856d4570a0f9056aeac2513dc71d8491/cesarin_max_config.json"
            val respuesta = URL(url).readText()
            configGist = JSONObject(respuesta)
            val cr = configGist?.getJSONObject("config_red")
            ssidEsperado = cr?.optString("ssid_esperado", "CESARINMAX")!!
            macRouterEsperado = cr?.optString("mac_router", "")!!
            val ca = configGist?.getJSONObject("app")
            numeroAdminWhatsapp = ca?.optString("numero_admin_whatsapp", "+51974634113")!!
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun verificarRed(): Boolean {
        if (MODO_PRUEBA_SIEMPRE) return true
        if (configGist?.getJSONObject("config_red")?.optBoolean("modo_prueba", false) == true) return true
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        val ssid = info.ssid.replace("\"", "").replace("<unknown ssid>", "")
        if (macRouterEsperado.isBlank()) return ssid.equals(ssidEsperado, ignoreCase = true)
        return ssid.equals(ssidEsperado, ignoreCase = true) &&
               info.bssid.equals(macRouterEsperado, ignoreCase = true)
    }

    private fun mostrarMensajeRedNoAutorizada() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Red no autorizada")
            .setMessage("Conéctate a la red WiFi \"$ssidEsperado\"")
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
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = userAgentString + " CESARINMAX/1.0"
        }
        webView.clearCache(true)
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // ✅ AQUÍ ESTÁ EL FIX DE WHATSAPP
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url ?: return false
                return when {
                    // Interceptar enlaces de WhatsApp
                    url.startsWith("whatsapp://") || url.startsWith("https://api.whatsapp.com")
                            || url.startsWith("https://wa.me/") -> {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ WhatsApp no instalado", Toast.LENGTH_LONG).show()
                        }
                        true // ← Decirle al WebView: no cargues esto, ya lo abrí yo
                    }
                    else -> false // ← Todo lo demás cargalo normal
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread { r.grant(r.resources) }
            }
        }
    }

    inner class WebAppInterface(private val ctx: MainActivity) {
        @JavascriptInterface
        fun premioGanado(jsonPremio: String) {
            val p = JSONObject(jsonPremio)
            val tipo = p.optString("tipo", "ninguno")
            val nombre = p.optString("nombre", "Premio")
            val minutos = p.optInt("minutos", 0)
            val prefijo = p.optString("prefijo_codigo", "")

            when (tipo) {
                // ⏰ Tiempo de internet → genera ticket en Firebase
                "internet" -> ctx.crearTicketTiempo(minutos, nombre, prefijo)

                // 📦 Productos y recargas → van a WhatsApp
                "producto", "recarga" -> ctx.enviarPorWhatsApp(nombre, tipo, prefijo)

                // Otros premios
                else -> Toast.makeText(ctx, "Ganaste: $nombre", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun crearTicketTiempo(minutos: Int, nombre: String, prefijo: String) {
        val codigo = generarCodigo(prefijo)
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val ticket = hashMapOf(
            "codigo" to codigo,
            "tiempo_minutos" to minutos,
            "monto" to 0.0,
            "fecha" to fecha,
            "nombre_premio" to nombre,
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
                mostrarDialogoGanaste(codigo, minutos, nombre)
            }
            .addOnFailureListener {
                Toast.makeText(this, "❌ Error guardando", Toast.LENGTH_SHORT).show()
            }
    }

    private fun mostrarDialogoGanaste(codigo: String, minutos: Int, nombre: String) {
        val tiempoStr = if (minutos >= 60) {
            val h = minutos / 60
            val m = minutos % 60
            if (m > 0) "$h h $m m" else "$h horas"
        } else "$minutos min"

        AlertDialog.Builder(this)
            .setTitle("🎉 ¡GANASTE!")
            .setMessage("🏆 $nombre\n\n🔢 Código: $codigo\n⏱️ Tiempo: $tiempoStr\n\nGuarda este código o compártelo.")
            .setPositiveButton("📤 COMPARTIR") { _, _ -> compartir(codigo, nombre, tiempoStr) }
            .setNegativeButton("✅ LISTO", null)
            .show()
    }

    fun enviarPorWhatsApp(nombre: String, tipo: String, prefijo: String) {
        val codigo = generarCodigoCorto(prefijo)
        val tipoStr = when (tipo) {
            "producto" -> "Producto"
            "recarga" -> "Recarga / Diamantes"
            else -> "Premio"
        }

        val msj = """
            🎉 ¡GANASTE EN LA RULETA CESARINMAX!
            
            🏆 Premio: $nombre
            📦 Tipo: $tipoStr
            🔢 Código: $codigo
            
            Por favor coordina la entrega.
        """.trimIndent()

        val num = numeroAdminWhatsapp.replace("+", "").replace(" ", "")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://api.whatsapp.com/send?phone=$num&text=${Uri.encode(msj)}")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "❌ WhatsApp no instalado", Toast.LENGTH_LONG).show()
        }
    }

    private fun generarCodigo(prefijo: String): String {
        val num = (100000..999999).random().toString()
        return if (prefijo.isNotEmpty()) "$prefijo-$num" else num
    }

    private fun generarCodigoCorto(prefijo: String): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..4).map { chars.random() }.joinToString("")
        return if (prefijo.isNotEmpty()) "$prefijo-$code" else code
    }

    private fun compartir(codigo: String, nombre: String, tiempo: String) {
        val txt = """
            🎟️ CESARINMAX — Código de canje
            🏆 Premio: $nombre
            🔢 Código: $codigo
            ⏱️ Tiempo: $tiempo
            
            ¡Canjéalo en el local!
        """.trimIndent()

        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, txt)
        }
        startActivity(Intent.createChooser(i, "Compartir código"))
    }

    private fun cargarPortal() {
        pedirPermisoCamara()
        val url = configGist?.getJSONObject("app")?.optString("enlace_portal_web")
            ?: "https://dulcet-pudding-45f043.netlify.app/?v=1"
        webView.loadUrl(url)
        Toast.makeText(this, "✅ Conectado a CESARINMAX", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        webView.evaluateJavascript(
            """
            (function(){
                if(typeof cerrarVentanaDesdeApp === 'function')
                    return cerrarVentanaDesdeApp() ? 'cerrado' : 'no';
                return 'no';
            })()
            """.trimIndent()
        ) { res ->
            val r = res.removeSurrounding("\"")
            when {
                r == "cerrado" -> {}
                webView.canGoBack() -> webView.goBack()
                else -> salir()
            }
        }
    }

    private fun salir() {
        AlertDialog.Builder(this)
            .setTitle("🚪 Salir")
            .setMessage("¿Salir de CESARINMAX?")
            .setPositiveButton("✅ Sí") { _, _ -> finishAffinity() }
            .setNegativeButton("❌ No", null)
            .show()
    }
}
