package com.cesarinmax.app

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
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

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.keepScreenOn = true
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
        webView.evaluateJavascript("document.querySelectorAll('video, audio').forEach(el => el.pause());", null)
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.evaluateJavascript("document.querySelectorAll('video, audio').forEach(el => { el.pause(); el.currentTime=0; });", null)
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private fun pedirPermisoCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "✅ Cámara habilitada", Toast.LENGTH_SHORT).show()
    }

    private fun cargarConfigGist() {
        try {
            val urlGist = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/a9d9d0b01d4b98944aeb18a53b25f55cb9fa816e/cesarin_max_config.json?t=" + System.currentTimeMillis()
            configGist = JSONObject(URL(urlGist).readText())
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
        return ssid.equals(ssidEsperado, ignoreCase = true) && info.bssid.equals(macRouterEsperado, ignoreCase = true)
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
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.clearCache(true)
        webView.clearHistory()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url ?: return false
                val u = url.lowercase()
                return when {
                    u.startsWith("whatsapp://") || u.startsWith("https://api.whatsapp.com") ||
                    u.startsWith("https://wa.me/") || u.startsWith("https://chat.whatsapp.com/") -> {
                        abrirEnlaceExterno(url!!)
                        true
                    }
                    u.startsWith("tel:") || u.startsWith("mailto:") -> {
                        abrirEnlaceExterno(url!!)
                        true
                    }
                    else -> false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = runOnUiThread { request.grant(request.resources) }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view; customViewCallback = callback
                originalSystemUiVisibility = window.decorView.systemUiVisibility
                val decor = window.decorView as FrameLayout
                decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            }
            override fun onHideCustomView() {
                val decor = window.decorView as FrameLayout
                customView?.let { decor.removeView(it) }
                customViewCallback?.onCustomViewHidden()
                customView = null; customViewCallback = null
                window.decorView.systemUiVisibility = originalSystemUiVisibility
            }
        }
    }

    private fun abrirEnlaceExterno(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageManager.queryIntentActivities(intent, 0).isNotEmpty()) startActivity(intent)
            else Toast.makeText(this, "❌ No hay aplicación para abrir este enlace", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    inner class WebAppInterface(private val ctx: MainActivity) {
        @JavascriptInterface
        fun premioGanado(jsonPremio: String) {
            val p = JSONObject(jsonPremio)
            when (p.optString("tipo", "ninguno")) {
                "internet" -> ctx.crearTicketTiempo(p.optInt("minutos", 0), p.optString("nombre", "Premio"), p.optString("prefijo_codigo", ""))
                "producto", "recarga" -> ctx.enviarPorWhatsApp(p.optString("nombre", "Premio"), p.optString("tipo", ""), p.optString("prefijo_codigo", ""))
                else -> Toast.makeText(ctx, "Ganaste: ${p.optString("nombre", "Premio")}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun crearTicketTiempo(minutos: Int, nombre: String, prefijo: String) {
        val codigo = generarCodigo(prefijo)
        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        FirebaseDatabase.getInstance().reference.child("historial").child(codigo)
            .setValue(mapOf(
                "codigo" to codigo, "tiempo_minutos" to minutos, "monto" to 0.0, "fecha" to fecha,
                "nombre_premio" to nombre, "origen" to "RULETA",
                "leido_por_ticket" to false, "leido_por_monedero" to false, "leido_por_portal" to false
            ))
            .addOnSuccessListener { mostrarDialogoGanaste(codigo, minutos, nombre) }
            .addOnFailureListener { Toast.makeText(this, "❌ Error guardando", Toast.LENGTH_SHORT).show() }
    }

    private fun mostrarDialogoGanaste(codigo: String, minutos: Int, nombre: String) {
        val tiempoStr = if (minutos >= 60) { val h = minutos / 60; val m = minutos % 60; if (m > 0) "$h h $m m" else "$h horas" } else "$minutos min"
        AlertDialog.Builder(this)
            .setTitle("🎉 ¡GANASTE!")
            .setMessage("🏆 $nombre\n\n🔢 Código: $codigo\n⏱️ Tiempo: $tiempoStr\n\nGuarda este código o compártelo.")
            .setPositiveButton("📤 COMPARTIR") { _, _ -> compartir(codigo, nombre, tiempoStr) }
            .setNegativeButton("✅ LISTO", null)
            .show()
    }

    fun enviarPorWhatsApp(nombre: String, tipo: String, prefijo: String) {
        val codigo = generarCodigoCorto(prefijo)
        val tipoStr = if (tipo == "producto") "Producto" else if (tipo == "recarga") "Recarga / Diamantes" else "Premio"
        val msj = """🎉 ¡GANASTE EN LA RULETA CESARINMAX!
            
🏆 Premio: $nombre
📦 Tipo: $tipoStr
🔢 Código: $codigo
            
Por favor coordina la entrega.""".trimIndent()
        val num = numeroAdminWhatsapp.replace("+", "").replace(" ", "")
        abrirEnlaceExterno("https://api.whatsapp.com/send?phone=$num&text=${Uri.encode(msj)}")
    }

    private fun generarCodigo(prefijo: String) = (if (prefijo.isNotEmpty()) "$prefijo-" else "") + (100000..999999).random()
    private fun generarCodigoCorto(prefijo: String): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..4).map { chars.random() }.joinToString("")
        return if (prefijo.isNotEmpty()) "$prefijo-$code" else code
    }

    private fun compartir(codigo: String, nombre: String, tiempo: String) {
        val txt = """🎟️ CESARINMAX — Código de canje
🏆 Premio: $nombre
🔢 Código: $codigo
⏱️ Tiempo: $tiempo
            
¡Canjéalo en el local!""".trimIndent()
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }
        startActivity(Intent.createChooser(i, "Compartir código"))
    }

    private fun cargarPortal() {
        pedirPermisoCamara()
        webView.clearCache(true)
        webView.clearHistory()
        val baseUrl = configGist?.getJSONObject("app")?.optString("enlace_portal_web")
            ?: "https://poetic-dodol-629897.netlify.app/"
        val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
        webView.loadUrl(url)
        Toast.makeText(this, "✅ Conectado a CESARINMAX", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (customView != null) { webView.webChromeClient?.onHideCustomView(); return }
        webView.evaluateJavascript("(function(){if(typeof cerrarVentanaDesdeApp==='function')return cerrarVentanaDesdeApp()?'cerrado':'no';return'no';})()") { res ->
            val r = res.removeSurrounding("\"")
            when { r == "cerrado" -> {}; webView.canGoBack() -> webView.goBack(); else -> salir() }
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
