package ir.kaveh.screenreader

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        }
    }

    /** موتور TTS فارسی؛ null یعنی موتور پیش‌فرض سیستم */
    var enginePackage: String?
        get() = sp.getString("engine", null)
        set(v) = sp.edit().putString("engine", v).apply()

    /** موتور جداگانه برای جمله‌های انگلیسی؛ null یعنی خودکار (Google اگر نصب باشد) */
    var englishEnginePackage: String?
        get() = sp.getString("engine_en", null)
        set(v) = sp.edit().putString("engine_en", v).apply()

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    var pitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    /** زبان OCR برای Tesseract: fas ، eng ، fas+eng */
    var ocrLang: String
        get() = sp.getString("ocr_lang", "fas+eng") ?: "fas+eng"
        set(v) = sp.edit().putString("ocr_lang", v).apply()

    /** بعد از تمام شدن صفحه، خودکار اسکرول کند و ادامه بدهد */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    /** برچسب دکمه‌ها و آیکون‌ها را نخواند */
    var skipButtons: Boolean
        get() = sp.getBoolean("skip_buttons", true)
        set(v) = sp.edit().putBoolean("skip_buttons", v).apply()

    /** جمله‌های انگلیسی را با صدای انگلیسی بخواند */
    var autoLanguage: Boolean
        get() = sp.getBoolean("auto_lang", true)
        set(v) = sp.edit().putBoolean("auto_lang", v).apply()

    var showPanel: Boolean
        get() = sp.getBoolean("show_panel", true)
        set(v) = sp.edit().putBoolean("show_panel", v).apply()

    // ── صدای ابری (Gemini TTS) ──
    var cloudEnabled: Boolean
        get() = sp.getBoolean("cloud_on", false)
        set(v) = sp.edit().putBoolean("cloud_on", v).apply()

    var cloudApiKey: String
        get() = sp.getString("cloud_key", "") ?: ""
        set(v) = sp.edit().putString("cloud_key", v).apply()

    var cloudModel: String
        get() = sp.getString("cloud_model", "gemini-2.5-flash-preview-tts") ?: "gemini-2.5-flash-preview-tts"
        set(v) = sp.edit().putString("cloud_model", v).apply()

    var cloudVoice: String
        get() = sp.getString("cloud_voice", "Kore") ?: "Kore"
        set(v) = sp.edit().putString("cloud_voice", v).apply()

    var cloudStyle: String
        get() = sp.getString("cloud_style", "") ?: ""
        set(v) = sp.edit().putString("cloud_style", v).apply()

    var panelX: Int
        get() = sp.getInt("panel_x", 0)
        set(v) = sp.edit().putInt("panel_x", v).apply()

    var panelY: Int
        get() = sp.getInt("panel_y", 400)
        set(v) = sp.edit().putInt("panel_y", v).apply()
}
