package com.cesarinmax.app

import android.app.AlertDialog
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var configGist: JSONObject? = null
    private var ssidEsperado = "CESARINMAX"
    private var macRouterEsperado = ""
    private val MODO_PRUEBA_SIEMPRE = true
    private val REQUEST_CAMERA = 1001
    private var numeroAdminWhatsapp = "+51974634113"

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var orientacionOriginal: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // ========== RADIO: Mantener audio activo con pantalla apagada ==========
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    @SuppressLint("WakelockTimeout")
    private fun mantenerAudioActivo() {
        // 🔒 Mantener CPU encendida
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "cesarinmax:radioWakeLock"
        )
        wakeLock?.acquire() // SIN límite de tiempo — la radio no se corta

        // 📶 Mantener WiFi encendido aunque se apague la pantalla
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cesarinmax:WifiLock")
        wifiLock?.acquire()

        // 🔊 PEDIR FOCO DE AUDIO — CLAVE para que Android no corte el audio
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(audioFocusChangeListener)
                build()
            }
            audioFocusRequest?.let { req ->
                val result = audioManager?.requestAudioFocus(req)
                audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> audioFocusGranted = true
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioFocusGranted = false
        }
    }

    private fun liberarBloqueos() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        // Soltar foco de audio al cerrar la app
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
    }
    // =======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.keepScreenOn = true
        
        webView = findViewById(R.id.webView)
        
        // ✅ DESLIZAR PARA ACTUALIZAR
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            0xFFFFCC00.toInt(),
            0xFFFF6600.toInt(),
            0xFF00CCFF.toInt()
        )
        swipeRefresh.setOnRefreshListener {
            webView.clearCache(true)
            webView.reload()
            swipeRefresh.isRefreshing = false
        }
        
        configurarWebView()
        
        // ✅ INICIAR MANTENIMIENTO DE AUDIO
        mantenerAudioActivo()
        
        CoroutineScope(Dispatchers.IO).launch {
            cargarConfigGist()
            withContext(Dispatchers.Main) {
                if (verificarRed()) cargarPortal()
                else mostrarMensajeRedNoAutorizada()
            }
        }
    }

    // ✅ NO PAUSAR NADA AL PERDER FOCO — EL AUDIO SIGUE
    override fun onPause() {
        // NO llamar a webView.onPause() — eso corta el audio
        // NO liberar wakeLock ni wifiLock aquí
        super.onPause()
        // Inyectar JS para que la página NO sepa que está en segundo plano
        webView.evaluateJavascript("""
            Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true });
            Object.defineProperty(document, 'hidden', { value: false, writable: true });
            document.dispatchEvent(new Event('visibilitychange'));
        """.trimIndent(), null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Reafirmar bloqueos
        if (wakeLock?.isHeld != true) mantenerAudioActivo()
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarBloqueos() // Solo liberar al cerrar la app
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
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
            val urlGist = "https://gist.githubusercontent.com/kilomkolim84-rgb/06685708f1b31fa79cd898b90333e315/raw/cesarin_max_config.json?t=" + System.currentTimeMillis()
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

    private fun ponerPantallaCompletaHorizontal() {
        orientacionOriginal = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun salirPantallaCompleta() {
        requestedOrientation = orientacionOriginal
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false // ✅ Auto-reproducción permitida
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = userAgentString + " CESARINMAX/1.0"
            allowFileAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setAllowUniversalAccessFromFileURLs(true)
            databaseEnabled = true
        }
        webView.clearCache(true)
        webView.clearHistory()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // ✅ EVITAR que la página detecte que está en segundo plano
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inyectar JS para falsificar estado de visibilidad
                webView.evaluateJavascript("""
                    (function(){
                        Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true, configurable: true });
                        Object.defineProperty(document, 'hidden', { value: false, writable: true, configurable: true });
                        window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                        console.log('✅ Visibilidad forzada a visible — radio sigue sonando');
                    })();
                """.trimIndent(), null)
            }

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
                customView = view
                customViewCallback = callback
                originalSystemUiVisibility = window.decorView.systemUiVisibility
                val decor = window.decorView as FrameLayout
                decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                ponerPantallaCompletaHorizontal()
            }

            override fun onHideCustomView() {
                val decor = window.decorView as FrameLayout
                customView?.let { decor.removeView(it) }
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
                window.decorView.systemUiVisibility = originalSystemUiVisibility
                salirPantallaCompleta()
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
        webView.loadUrl("file:///android_asset/index.html")
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
