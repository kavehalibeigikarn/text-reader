package ir.kaveh.screenreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * لایه TTS مشترک بین سرویس، صفحه تنظیمات و منوی «بلند بخوان».
 * متن به جمله‌ها تقسیم می‌شود تا توقف/ادامه از همان جمله ممکن باشد.
 */
object Speaker : TextToSpeech.OnInitListener {

    enum class State { IDLE, SPEAKING, PAUSED }

    val persian: Locale = Locale.forLanguageTag("fa-IR")
    private val english: Locale = Locale.US

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    var ready = false
        private set
    var state = State.IDLE
        private set

    private var chunks: List<String> = emptyList()
    private var index = 0
    private var session = 0
    private var pendingText: String? = null

    private val listeners = LinkedHashSet<(State) -> Unit>()

    /** وقتی آخرین جمله تمام شد (برای حالت خواندن پیوسته) */
    var onFinished: (() -> Unit)? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (tts == null) create()
    }

    /** بعد از عوض کردن موتور TTS صدا زده می‌شود */
    fun reload() {
        stop()
        create()
    }

    private fun create() {
        ready = false
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = TextToSpeech(appCtx, this, Prefs.enginePackage)
    }

    override fun onInit(status: Int) {
        main.post {
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                notifyState()
                return@post
            }
            ready = true
            tts?.setOnUtteranceProgressListener(progress)
            notifyState()
            pendingText?.let { t ->
                pendingText = null
                speak(t)
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            main.post { if (s == session) index = i }
        }

        override fun onDone(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            main.post {
                if (s == session && i >= chunks.lastIndex) {
                    state = State.IDLE
                    notifyState()
                    onFinished?.invoke()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onDone(utteranceId)
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        if (id == null || !id.startsWith("s")) return null
        val parts = id.substring(1).split('_')
        if (parts.size != 2) return null
        val s = parts[0].toIntOrNull() ?: return null
        val i = parts[1].toIntOrNull() ?: return null
        return s to i
    }

    fun speak(text: String) {
        val clean = TextTools.normalize(text)
        if (clean.isEmpty()) return
        if (!ready) {
            pendingText = clean
            return
        }
        chunks = TextTools.chunk(clean)
        if (chunks.isEmpty()) return
        startFrom(0)
    }

    private fun startFrom(i: Int) {
        val t = tts ?: return
        session++
        t.stop()
        t.setSpeechRate(Prefs.speechRate)
        t.setPitch(Prefs.pitch)
        for (j in i until chunks.size) {
            val c = chunks[j]
            val loc = if (Prefs.autoLanguage && TextTools.isMostlyLatin(c)) english else persian
            t.setLanguage(loc)   // زبان هنگام صدا زدن speak ثبت می‌شود
            t.speak(c, TextToSpeech.QUEUE_ADD, null, "s${session}_$j")
        }
        index = i
        state = State.SPEAKING
        notifyState()
    }

    fun pause() {
        if (state != State.SPEAKING) return
        session++
        tts?.stop()
        state = State.PAUSED
        notifyState()
    }

    fun resume() {
        if (state == State.PAUSED && chunks.isNotEmpty()) startFrom(index.coerceIn(0, chunks.lastIndex))
    }

    fun togglePause() {
        when (state) {
            State.SPEAKING -> pause()
            State.PAUSED -> resume()
            State.IDLE -> {}
        }
    }

    fun stop() {
        session++
        tts?.stop()
        chunks = emptyList()
        index = 0
        pendingText = null
        if (state != State.IDLE) {
            state = State.IDLE
            notifyState()
        }
    }

    /** جمله قبلی / بعدی */
    fun skip(delta: Int) {
        if (chunks.isEmpty()) return
        startFrom((index + delta).coerceIn(0, chunks.lastIndex))
    }

    fun engines(): List<TextToSpeech.EngineInfo> = tts?.engines ?: emptyList()
    fun defaultEngine(): String? = tts?.defaultEngine

    /** وضعیت پشتیبانی فارسی در موتور فعلی (یکی از ثابت‌های TextToSpeech.LANG_*) */
    fun persianSupport(): Int =
        if (!ready) TextToSpeech.ERROR
        else tts?.isLanguageAvailable(persian) ?: TextToSpeech.ERROR

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun notifyState() {
        for (l in listeners.toList()) l(state)
    }
}
