package com.cesarinmax.app

import android.app.AlertDialog
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
    
    // ========== 🔒 VERIFICACIÓN POR RANGO DE IP ==========
    private val PERMITIDO_IP1 = 172
    private val PERMITIDO_IP2 = 16
    private val PERMITIDO_IP3 = 201
    private val PERMITIDO_INICIO = 1
    private val PERMITIDO_FIN = 254
    // =====================================================
    
    private val REQUEST_CAMERA = 1001
    private val REQUEST_UBICACION = 1002
    private var numeroAdminWhatsapp = "+51974634113"

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var orientacionOriginal: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // ========== 🎵 RADIO — Audio en segundo plano ==========
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private var audioFocusGranted = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        audioFocusGranted = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> true
            else -> false
        }
    }

    private fun mantenerAudioActivo() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "cesarinmax:radioWakeLock"
        )
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cesarinmax:WifiLock")
        wifiLock?.acquire()

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

        mediaSession = MediaSession(this, "CESARINMAX_RADIO").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {})
            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(state)
            isActive = true
        }
    }

    private fun liberarBloqueos() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
        mediaSession?.isActive = false
        mediaSession?.release()
    }
    // ===============================================================

    // ========== 🔒 VERIFICACIÓN POR RANGO DE IP ==========
    private fun verificarRed(): Boolean {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        if (!wifi.isWifiEnabled) return false
        val ipInt = wifi.connectionInfo.ipAddress
        if (ipInt == 0) return false

        val a = ipInt and 0xFF
        val b = (ipInt shr 8) and 0xFF
        val c = (ipInt shr 16) and 0xFF
        val d = (ipInt shr 24) and 0xFF

        val enRango = (a == PERMITIDO_IP1 && b == PERMITIDO_IP2 && c == PERMITIDO_IP3 && d in PERMITIDO_INICIO..PERMITIDO_FIN)
        android.util.Log.d("CESARINMAX", "IP: $a.$b.$c.$d | Permitido: $enRango")
        return enRango
    }
    // =====================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.keepScreenOn = true
        
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            0xFFFFCC00.toInt(),
            0xFFFF6600.toInt(),
            0xFF00CCFF.toInt()
        )
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener {
            webView.clearCache(true)
            webView.reload()
            swipeRefresh.isRefreshing = false
        }
        
        configurarWebView()
        mantenerAudioActivo()
        
        CoroutineScope(Dispatchers.IO).launch {
            cargarConfigGist()
            withContext(Dispatchers.Main) { cargarPortal() }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("javascript:pausarMusicaFondo();", null)
        if (wakeLock?.isHeld != true) mantenerAudioActivo()
        mediaSession?.isActive = true
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.evaluateJavascript("javascript:reproducirMusicaFondo();", null)
        if (wakeLock?.isHeld != true) mantenerAudioActivo()
        mediaSession?.isActive = true
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarBloqueos()
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
    }

    private fun pedirPermisoUbicacion() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_UBICACION
            )
        }
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
            val ca = configGist?.getJSONObject("app")
            numeroAdminWhatsapp = ca?.optString("numero_admin_whatsapp", "+51974634113")!!
        } catch (e: Exception) { e.printStackTrace() }
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
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = userAgentString + " CESARINMAX/1.0"
            allowFileAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setAllowUniversalAccessFromFileURLs(true)
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
        if (!verificarRed()) {
            Toast.makeText(this, "❌ ACCESO RESTRINGIDO — Conéctate al WiFi CESARINMAX DE PAOYHAN", Toast.LENGTH_SHORT).show()
            return
        }
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
        if (!verificarRed()) {
            Toast.makeText(this, "❌ ACCESO RESTRINGIDO — Conéctate al WiFi CESARINMAX DE PAOYHAN", Toast.LENGTH_SHORT).show()
            return
        }
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
        pedirPermisoUbicacion()
        pedirPermisoCamara()
        
        val enRango = verificarRed()

        if (!enRango) {
            // ❌ FUERA DE RANGO — PANTALLA DE ACCESO RESTRINGIDO
            Toast.makeText(this, "❌ ACCESO RESTRINGIDO — CONÉCTATE AL WIFI CESARINMAX DE PAOYHAN", Toast.LENGTH_LONG).show()
            
            webView.loadDataWithBaseURL(null, """
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        * { margin:0; padding:0; box-sizing:border-box; font-family:Arial, sans-serif; }
                        body {
                            background: linear-gradient(135deg, #000000, #1a1a1a);
                            min-height: 100vh;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            justify-content: center;
                            color: #ffffff;
                            text-align: center;
                            padding: 20px;
                        }
                        .icon { font-size: 48px; margin-bottom: 20px; }
                        .titulo {
                            font-size: 32px;
                            font-weight: bold;
                            color: #ffcc00;
                            letter-spacing: 2px;
                            margin-bottom: 50px;
                        }
                        .red {
                            font-size: 24px;
                            line-height: 1.8;
                            margin-bottom: 50px;
                        }
                        .red .nombre {
                            font-size: 42px;
                            font-weight: 900;
                            color: #ffcc00;
                            display: block;
                            margin: 10px 0;
                        }
                        .red .de {
                            font-size: 28px;
                            color: #eeeeee;
                        }
                        .disfruta {
                            font-size: 22px;
                            color: #ffcc00;
                            margin-bottom: 60px;
                        }
                        .pasos {
                            font-size: 14px;
                            color: #999999;
                            line-height: 2;
                            border-top: 1px solid #333;
                            padding-top: 30px;
                        }
                    </style>
                </head>
                <body>
                    <div class="icon">🔒</div>
                    <div class="titulo">ACCESO RESTRINGIDO</div>
                    
                    <div class="red">
                        CONÉCTATE AL WIFI
                        <span class="nombre">CESARINMAX</span>
                        <span class="de">DE PAOYHAN</span>
                    </div>
                    
                    <div class="disfruta">¡DISFRUTA DE TODO! 🎉</div>
                    
                    <div class="pasos">
                        CONÉCTATE A LA RED OFICIAL<br>
                        VUELVE A ABRIR LA APLICACIÓN
                    </div>
                </body>
                </html>
            """.trimIndent(), "text/html", "UTF-8", null)
            return
        }

        // ✅ DENTRO DEL RANGO — OCULTA PREMIOS Y RULETA
        Toast.makeText(this, "✅ ACCESO PERMITIDO", Toast.LENGTH_SHORT).show()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("file:///android_asset/index.html")

        // 🔥 OCULTAR "PREMIOS", RULETA Y TODO LO QUE NO QUIERES
        webView.evaluateJavascript("""
            // OCULTAR POR CLASE: premios, premio, ruleta, etc
            document.querySelectorAll('.premios, .premio, #premios, #premio, .ruleta, .roulette, .wheel, .juego, .sorteo, .boton-ruleta, .seccion-ruleta, #ruleta').forEach(el => {
                el.style.display = 'none';
                el.style.visibility = 'hidden';
                el.style.opacity = '0';
                el.style.height = '0';
                el.style.overflow = 'hidden';
            });
            
            // OCULTAR TODO LO QUE DIGA "PREMIOS" en texto
            document.querySelectorAll('a, button, div, span, li').forEach(el => {
                const texto = el.textContent.trim().toUpperCase();
                if(texto === 'PREMIOS' || texto.includes('PREMIO')) {
                    el.style.display = 'none';
                    el.style.visibility = 'hidden';
                }
            });
            
            console.log('✅ PREMIOS OCULTADOS — Solo Noticias, Deportes, Radios');
        """.trimIndent(), null)
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
