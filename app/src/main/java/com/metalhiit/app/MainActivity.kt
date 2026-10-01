package com.metalhiit.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var voice: MetalVoice
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La pantalla NUNCA se apaga mientras la app esté abierta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Los botones de volumen controlan la música.
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.statusBarColor = Color.parseColor("#16181A")

        voice = MetalVoice(this)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#16181A"))
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(pick, PICK_AUDIO)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Uso intencional sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_AUDIO) return
        val cb = fileCallback ?: return
        fileCallback = null
        if (resultCode != RESULT_OK || data == null) {
            cb.onReceiveValue(null); return
        }
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        cb.onReceiveValue(uris.toTypedArray())
    }

    @Deprecated("Uso intencional sin AndroidX")
    override fun onBackPressed() {
        // Si hay un entrenamiento en curso, el botón atrás lo detiene en vez de cerrar la app.
        web.evaluateJavascript("window.androidBack ? window.androidBack() : false") { result ->
            if (result != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        voice.release()
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun voiceStatus(): String = voice.status
        @JavascriptInterface fun cue(key: String): Int = voice.play(key)
        @JavascriptInterface fun setVoiceVolume(v: Float) { voice.volume = v.coerceIn(0f, 1f) }
        @JavascriptInterface fun prepareNames(json: String) {
            val arr = JSONArray(json)
            voice.prepareNames((0 until arr.length()).map { arr.getString(it) })
        }
        @JavascriptInterface fun regenerate(intensity: Float) { voice.regenerate(intensity.coerceIn(0f, 1f)) }
        @JavascriptInterface fun openTtsSettings() {
            runOnUiThread {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {}
            }
        }
    }

    companion object { private const val PICK_AUDIO = 42 }
}
