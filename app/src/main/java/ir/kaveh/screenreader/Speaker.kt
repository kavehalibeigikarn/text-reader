package ir.kaveh.screenreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * لایه TTS مشترک بین سرویس، صفحه تنظیمات و منوی «بلند بخوان».
 * دو موتور جدا: یکی برای فارسی، یکی برای انگلیسی (چون موتورهای فارسی معمولاً انگلیسی بلد نیستند).
 * جمله‌ها یکی‌یکی پخش می‌شوند تا بین دو موتور جابه‌جا شود و توقف/ادامه از همان جمله ممکن باشد.
 */
object Speaker {

    enum class State { IDLE, SPEAKING, PAUSED }

    const val GOOGLE_TTS = "com.google.android.tts"

    val persian: Locale = Locale.forLanguageTag("fa-IR")
    private val english: Locale = Locale.US

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())

    private var faTts: TextToSpeech? = null
    private var enTts: TextToSpeech? = null
    private var faReady = false
    private var enReady = false

    val ready: Boolean get() = faReady

    var state = State.IDLE
        private set

    private var chunks: List<String> = emptyList()
    private var index = 0
    private var session = 0
    private var pendingText: String? = null
    private var warnedNoPersian = false

    private val listeners = LinkedHashSet<(State) -> Unit>()

    /** وقتی آخرین جمله تمام شد (برای حالت خواندن پیوسته) */
    var onFinished: (() -> Unit)? = null

    /** پیام هشدار برای کاربر (مثلاً نبود صدای فارسی) */
    var onWarning: ((String) -> Unit)? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (faTts == null) create()
    }

    /** بعد از عوض کردن موتورها صدا زده می‌شود */
    fun reload() {
        stop()
        create()
    }

    private fun create() {
        faReady = false
        enReady = false
        warnedNoPersian = false
        try { faTts?.shutdown() } catch (_: Exception) {}
        try { enTts?.shutdown() } catch (_: Exception) {}
        enTts = null

        faTts = TextToSpeech(appCtx, { status ->
            main.post {
                faReady = status == TextToSpeech.SUCCESS
                if (faReady) {
                    faTts?.setOnUtteranceProgressListener(progress)
                    createEnglish()
                }
                notifyState()
                if (faReady) pendingText?.let { t -> pendingText = null; speak(t) }
            }
        }, Prefs.enginePackage)
    }

    /** موتور انگلیسی بعد از آماده شدن موتور فارسی ساخته می‌شود تا فهرست موتورها در دسترس باشد */
    private fun createEnglish() {
        val pkg = englishEngine()
        val faPkg = Prefs.enginePackage ?: faTts?.defaultEngine
        if (pkg == null || pkg == faPkg) {
            enTts = null   // همان موتور فارسی استفاده می‌شود
            return
        }
        enTts = TextToSpeech(appCtx, { status ->
            main.post {
                enReady = status == TextToSpeech.SUCCESS
                if (enReady) enTts?.setOnUtteranceProgressListener(progress)
            }
        }, pkg)
    }

    /** موتور انگلیسی انتخاب‌شده، یا Google اگر نصب باشد */
    fun englishEngine(): String? {
        Prefs.englishEnginePackage?.let { return it }
        val installed = faTts?.engines?.map { it.name } ?: emptyList()
        return if (GOOGLE_TTS in installed) GOOGLE_TTS else null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            val (s, i) = parse(utteranceId) ?: return
            main.post {
                if (s != session) return@post
                if (i < chunks.lastIndex) {
                    speakChunk(i + 1)
                } else {
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

    fun cloudOn(): Boolean = Prefs.cloudEnabled && Prefs.cloudApiKey.isNotBlank()

    fun speak(text: String) {
        val clean = TextTools.normalize(text)
        if (clean.isEmpty()) return
        if (!faReady && !cloudOn()) {
            pendingText = clean
            return
        }
        chunks = TextTools.chunk(clean, if (cloudOn()) 700 else 350)
        if (chunks.isEmpty()) return

        if (!cloudOn() && !warnedNoPersian && persianSupport() < TextToSpeech.LANG_AVAILABLE &&
            chunks.any { !TextTools.isMostlyLatin(it) }
        ) {
            warnedNoPersian = true
            onWarning?.invoke("موتور صدای انتخاب‌شده فارسی ندارد؛ در برنامه صفحه‌خوان یک موتور فارسی انتخاب کنید")
        }
        startFrom(0)
    }

    private fun startFrom(i: Int) {
        session++
        faTts?.stop()
        enTts?.stop()
        CloudTts.stop()
        state = State.SPEAKING
        notifyState()

        if (cloudOn()) {
            index = i
            val sess = session
            CloudTts.start(
                chunks, i,
                onIndex = { j -> if (sess == session) index = j },
                onDone = {
                    if (sess == session) {
                        state = State.IDLE
                        notifyState()
                        onFinished?.invoke()
                    }
                },
                onError = { msg ->
                    if (sess == session) {
                        state = State.IDLE
                        notifyState()
                        onWarning?.invoke("صدای ابری: $msg")
                    }
                }
            )
            return
        }
        speakChunk(i)
    }

    private fun speakChunk(j: Int) {
        if (j !in chunks.indices) return
        index = j
        val c = chunks[j]
        val latin = Prefs.autoLanguage && TextTools.isMostlyLatin(c)
        val en = enTts
        val t = if (latin && en != null && enReady) en else faTts ?: return
        t.setSpeechRate(Prefs.speechRate)
        t.setPitch(Prefs.pitch)
        t.setLanguage(if (latin) english else persian)
        t.speak(c, TextToSpeech.QUEUE_FLUSH, null, "s${session}_$j")
    }

    fun pause() {
        if (state != State.SPEAKING) return
        session++
        faTts?.stop()
        enTts?.stop()
        CloudTts.stop()
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
        faTts?.stop()
        enTts?.stop()
        CloudTts.stop()
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

    fun engines(): List<TextToSpeech.EngineInfo> = faTts?.engines ?: emptyList()
    fun defaultEngine(): String? = faTts?.defaultEngine

    /** وضعیت پشتیبانی فارسی در موتور فارسی (یکی از ثابت‌های TextToSpeech.LANG_*) */
    fun persianSupport(): Int =
        if (!faReady) TextToSpeech.ERROR
        else faTts?.isLanguageAvailable(persian) ?: TextToSpeech.ERROR

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun notifyState() {
        for (l in listeners.toList()) l(state)
    }
}
