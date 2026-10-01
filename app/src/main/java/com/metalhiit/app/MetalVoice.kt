package com.metalhiit.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Genera la "voz de locutor de metal": el motor de voz del teléfono dice cada frase,
 * la app la graba en un archivo y la procesa (más grave, distorsión y reverberación de estadio).
 * Las frases quedan guardadas, así que solo se generan la primera vez.
 */
class MetalVoice(private val ctx: Context) {

    @Volatile var status = "loading"      // loading | ready | error:<motivo>
    @Volatile var volume = 1f

    private val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private var intensity = prefs.getFloat("intensity", 0.7f)

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        ).build()

    private val soundIds = ConcurrentHashMap<String, Int>()
    private val durations = ConcurrentHashMap<String, Int>()
    private val loaded = ConcurrentHashMap<Int, Boolean>()
    private val pendingByUtterance = ConcurrentHashMap<String, Pair<String, File>>()
    private val pending = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor()
    private val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private var englishOk = true
    private var spanishOk = false

    private val phrases = linkedMapOf(
        "ready" to "Are you ready?",
        "5" to "Five!", "4" to "Four!", "3" to "Three!", "2" to "Two!", "1" to "One!",
        "go" to "Go!",
        "stop" to "Stop!",
        "round" to "Round complete!",
        "last" to "Last round!",
        "next" to "Next!",
        "done" to "Workout complete! You crushed it!"
    )

    init {
        pool.setOnLoadCompleteListener { _, id, st -> if (st == 0) loaded[id] = true }
        tts = TextToSpeech(ctx) { st ->
            if (st != TextToSpeech.SUCCESS) {
                status = "error:tts"
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            spanishOk = t.isLanguageAvailable(Locale("es")) >= TextToSpeech.LANG_AVAILABLE
            val r = t.setLanguage(Locale.US)
            englishOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { finished(id, true) }
                @Deprecated("Requerido por la API")
                override fun onError(id: String) { finished(id, false) }
                override fun onError(id: String, code: Int) { finished(id, false) }
            })
            buildBase()
        }
    }

    private fun tag() = "i" + (intensity * 100).toInt()
    private fun fileFor(key: String) =
        File(dir, "${key.hashCode().toUInt()}_${tag()}.wav")

    private fun buildBase() {
        status = "loading"
        val t = tts ?: return
        t.setLanguage(Locale.US)
        t.setPitch(0.55f)
        t.setSpeechRate(0.95f)
        for ((key, text) in phrases) request(key, text)
        checkReady()
    }

    /** Nombres de ejercicios: se dicen en español si el teléfono tiene esa voz. */
    fun prepareNames(names: List<String>) {
        val t = tts ?: return
        val missing = names.distinct().filter { !soundIds.containsKey("n:$it") }
        if (missing.isEmpty()) return
        status = "loading"
        if (spanishOk) t.setLanguage(Locale("es"))
        for (n in missing) request("n:$n", n)
        checkReady()
    }

    fun regenerate(newIntensity: Float) {
        intensity = newIntensity
        prefs.edit().putFloat("intensity", newIntensity).apply()
        soundIds.values.forEach { pool.unload(it) }
        soundIds.clear(); durations.clear()
        dir.listFiles()?.forEach { it.delete() }
        buildBase()
    }

    private fun request(key: String, text: String) {
        val out = fileFor(key)
        if (out.exists() && out.length() > 44) {
            load(key, out)
            return
        }
        val raw = File(ctx.cacheDir, "raw_${key.hashCode().toUInt()}.wav")
        val id = "u" + System.nanoTime()
        pendingByUtterance[id] = key to raw
        pending.incrementAndGet()
        tts?.synthesizeToFile(text, Bundle(), raw, id)
    }

    private fun finished(id: String, ok: Boolean) {
        val (key, raw) = pendingByUtterance.remove(id) ?: return
        worker.execute {
            try {
                if (ok) {
                    val out = fileFor(key)
                    MetalDsp.process(raw, out, intensity)
                    load(key, out)
                }
            } catch (_: Exception) {
            } finally {
                raw.delete()
                pending.decrementAndGet()
                checkReady()
            }
        }
    }

    private fun load(key: String, f: File) {
        durations[key] = MetalDsp.speechMs(f)
        soundIds[key] = pool.load(f.absolutePath, 1)
    }

    private fun checkReady() {
        if (pending.get() == 0) {
            status = if (!englishOk) "error:english" else "ready"
            // Tras los nombres en español, vuelve al inglés para las frases fijas.
            tts?.setLanguage(Locale.US)
        }
    }

    /** Reproduce una frase. Devuelve cuánto dura la voz (ms) o -1 si no existe. */
    fun play(key: String): Int {
        val id = soundIds[key] ?: return -1
        if (loaded[id] != true) return -1
        pool.play(id, volume, volume, 1, 0, 1f)
        return durations[key] ?: 800
    }

    fun release() {
        tts?.shutdown()
        pool.release()
        worker.shutdown()
    }
}

/** Procesamiento de audio: voz grave, distorsión tipo amplificador y reverberación. */
object MetalDsp {

    fun process(input: File, output: File, intensity: Float) {
        val (raw, rate) = readWav(input.readBytes())
        var x = trim(raw, rate)

        // 1. Más grave: estirar la señal baja el tono (y la hace un poco más lenta).
        x = resample(x, 1f + 0.22f * intensity)

        // 2. Distorsión suave (saturación tipo amplificador de guitarra).
        val drive = 1f + 7f * intensity
        val norm = tanh(drive)
        for (i in x.indices) x[i] = (tanh(x[i] * drive) / norm) * 0.85f + x[i] * 0.15f

        // 3. Filtro para quitar el chirrido agudo de la distorsión.
        var lp = 0f
        val a = 0.55f
        for (i in x.indices) { lp += a * (x[i] - lp); x[i] = lp }

        // 4. Reverberación de estadio.
        x = reverb(x, rate, wet = 0.18f + 0.22f * intensity)

        // 5. Normalizar al máximo volumen sin saturar.
        var peak = 0.0001f
        for (v in x) peak = max(peak, abs(v))
        val g = 0.95f / peak
        for (i in x.indices) x[i] *= g

        writeWav(output, x, rate)
    }

    /** Duración aproximada de la parte hablada (sin la cola de reverberación). */
    fun speechMs(f: File): Int {
        val (x, rate) = readWav(f.readBytes())
        var last = 0
        for (i in x.indices) if (abs(x[i]) > 0.12f) last = i
        return max(250, (last * 1000L / rate).toInt())
    }

    private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, p: Int) = le16(b, p) or (le16(b, p + 2) shl 16)

    fun readWav(b: ByteArray): Pair<FloatArray, Int> {
        var rate = 22050; var channels = 1; var bits = 16
        var dataOff = -1; var dataLen = 0
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val len = le32(b, p + 4)
            if (id == "fmt ") {
                channels = le16(b, p + 10)
                rate = le32(b, p + 12)
                bits = le16(b, p + 22)
            } else if (id == "data") {
                dataOff = p + 8
                dataLen = if (len <= 0 || len > b.size - dataOff) b.size - dataOff else len
                break
            }
            if (len < 0) break
            p += 8 + len + (len and 1)
        }
        if (dataOff < 0 || bits != 16) return FloatArray(0) to rate
        val frames = dataLen / (2 * max(1, channels))
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) {
                val o = dataOff + (i * channels + c) * 2
                s += (le16(b, o).toShort().toFloat() / 32768f)
            }
            out[i] = s / channels
        }
        return out to rate
    }

    private fun trim(x: FloatArray, rate: Int): FloatArray {
        if (x.isEmpty()) return x
        var s = 0; var e = x.size - 1
        while (s < x.size && abs(x[s]) < 0.01f) s++
        while (e > s && abs(x[e]) < 0.01f) e--
        val pad = rate / 40
        s = max(0, s - pad); e = min(x.size - 1, e + pad)
        return x.copyOfRange(s, e + 1)
    }

    private fun resample(x: FloatArray, factor: Float): FloatArray {
        val n = (x.size * factor).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val src = i / factor
            val j = src.toInt()
            val f = src - j
            val a = x[min(j, x.size - 1)]
            val b = x[min(j + 1, x.size - 1)]
            out[i] = a + (b - a) * f
        }
        return out
    }

    private fun reverb(x: FloatArray, rate: Int, wet: Float): FloatArray {
        val tail = (rate * 0.9f).toInt()
        val n = x.size + tail
        val input = FloatArray(n); x.copyInto(input)
        val sum = FloatArray(n)
        val combMs = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
        for (ms in combMs) {
            val d = (rate * ms / 1000f).toInt()
            val buf = FloatArray(n)
            for (i in 0 until n) {
                val fb = if (i >= d) buf[i - d] else 0f
                buf[i] = input[i] + fb * 0.8f
                sum[i] += buf[i] * 0.25f
            }
        }
        var y = sum
        for ((ms, gain) in listOf(5f to 0.7f, 1.7f to 0.7f)) {
            val d = (rate * ms / 1000f).toInt()
            val out = FloatArray(n)
            for (i in 0 until n) {
                val xd = if (i >= d) y[i - d] else 0f
                val yd = if (i >= d) out[i - d] else 0f
                out[i] = -gain * y[i] + xd + gain * yd
            }
            y = out
        }
        val res = FloatArray(n)
        for (i in 0 until n) res[i] = input[i] * (1f - wet * 0.5f) + y[i] * wet
        return res
    }

    private fun writeWav(f: File, x: FloatArray, rate: Int) {
        val dataLen = x.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (v in x) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
    }
}
