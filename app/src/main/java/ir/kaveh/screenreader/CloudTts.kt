package ir.kaveh.screenreader

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.security.MessageDigest

/**
 * خواندن متن با صدای ابری Gemini TTS.
 * هر تکه متن جداگانه گرفته و پخش می‌شود و تکه بعدی هم‌زمان با پخش، از پیش دانلود می‌شود.
 */
object CloudTts {

    private lateinit var appCtx: Context
    private var androidCert: String? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** اثر انگشت SHA-1 امضای برنامه؛ برای کلیدهایی که به این اپ محدود شده‌اند */
    private fun certSha1(): String? {
        androidCert?.let { return it }
        return try {
            val pm = appCtx.packageManager
            val info = pm.getPackageInfo(appCtx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            val md = MessageDigest.getInstance("SHA1").digest(sig.toByteArray())
            md.joinToString("") { "%02X".format(it) }.also { androidCert = it }
        } catch (_: Exception) { null }
    }

    private val net = Executors.newFixedThreadPool(2)
    private val player = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var session = 0
    @Volatile private var track: AudioTrack? = null
    private val cache = ConcurrentHashMap<Int, Future<ByteArray>>()

    fun stop() {
        session++
        cache.clear()
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
    }

    /** پخش تکه‌ها از ایندکس from تا آخر */
    fun start(
        chunks: List<String>,
        from: Int,
        onIndex: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val sess = session
        player.execute {
            try {
                var i = from
                while (i < chunks.size) {
                    if (sess != session) return@execute
                    val audio = fetchCached(chunks, i).get()
                    if (sess != session) return@execute
                    main.post { onIndex(i) }
                    if (i + 1 < chunks.size) fetchCached(chunks, i + 1)   // پیش‌دانلود تکه بعدی
                    play(audio, sess)
                    cache.remove(i)
                    i++
                }
                if (sess == session) main.post { onDone() }
            } catch (e: Throwable) {
                if (sess == session) {
                    val msg = e.cause?.message ?: e.message ?: "خطای نامشخص"
                    main.post { onError(msg) }
                }
            }
        }
    }

    private fun fetchCached(chunks: List<String>, i: Int): Future<ByteArray> =
        cache.getOrPut(i) { net.submit<ByteArray> { request(chunks[i]) } }

    // ───────────────────────── شبکه ─────────────────────────

    private fun request(text: String): ByteArray {
        val key = Prefs.cloudApiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("کلید API وارد نشده")

        val model = Prefs.cloudModel.trim().ifEmpty { "gemini-2.5-flash-preview-tts" }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")

        val prompt = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", Prefs.cloudStyle.trim()
                    .ifEmpty { "این متن را با لحن طبیعی، روان و بدون اضافه‌کردن هیچ کلمه‌ای بخوان:" } + "\n\n" + text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig",
                        JSONObject().put("voiceName", Prefs.cloudVoice))))
            })
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
            if (::appCtx.isInitialized) {
                setRequestProperty("X-Android-Package", appCtx.packageName)
                certSha1()?.let { setRequestProperty("X-Android-Cert", it) }
            }
        }
        conn.outputStream.use { it.write(prompt.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        conn.disconnect()

        if (code !in 200..299) throw IllegalStateException(describe(code, body))

        val json = JSONObject(body)
        val b64 = findAudioData(json) ?: throw IllegalStateException("پاسخ صوتی دریافت نشد: ${body.take(200)}")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /** خطا را به پیام قابل‌فهم تبدیل می‌کند */
    private fun describe(code: Int, body: String): String {
        val t = body.trim()
        if (t.startsWith("<")) {
            val plain = t.replace(Regex("(?s)<script.*?</script>"), " ")
                .replace(Regex("(?s)<style.*?</style>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return "خطای $code — پاسخ HTML به‌جای JSON: ${plain.take(220)}"
        }
        val msg = try {
            JSONObject(t).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }
        return if (msg.isNotEmpty()) "خطای $code: $msg" else "خطای $code: ${t.take(220)}"
    }

    /** آزمایش سریع تنظیمات ابری */
    fun test(callback: (Boolean, String) -> Unit) {
        net.execute {
            val result = try {
                val bytes = request("سلام، این یک آزمایش است.")
                true to "موفق بود. ${bytes.size / 1024} کیلوبایت صدا دریافت شد."
            } catch (e: Throwable) {
                false to (e.message ?: e.toString())
            }
            main.post { callback(result.first, result.second) }
        }
    }

    /** در ساختار پاسخ دنبال داده صوتی base64 می‌گردد (سازگار با شکل‌های مختلف پاسخ) */
    private fun findAudioData(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val d = node.opt("data")
                if (d is String && d.length > 500) return d
                for (k in node.keys()) {
                    findAudioData(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findAudioData(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ───────────────────────── پخش ─────────────────────────

    /** خروجی Gemini صوت خام PCM 16bit مونو با نرخ ۲۴۰۰۰ است */
    private fun play(pcm: ByteArray, sess: Int) {
        if (pcm.isEmpty()) return
        val rate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        try {
            val speed = Prefs.speechRate.coerceIn(0.5f, 2.0f)
            t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(Prefs.pitch.coerceIn(0.5f, 2.0f))
        } catch (_: Exception) {}

        t.play()
        var offset = 0
        while (offset < pcm.size) {
            if (sess != session) break
            val written = t.write(pcm, offset, minOf(minBuf, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        // صبر تا پایان پخش بافر
        val frames = pcm.size / 2
        while (sess == session && t.playbackHeadPosition < frames && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            Thread.sleep(50)
        }
        try { t.stop() } catch (_: Exception) {}
        t.release()
        if (track === t) track = null
    }
}
