package ir.kaveh.screenreader

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvService: TextView
    private lateinit var tvLang: TextView
    private lateinit var spEngine: Spinner
    private lateinit var spEngineEn: Spinner
    private lateinit var tvRate: TextView
    private lateinit var tvPitch: TextView

    private var enginePkgs: List<String> = emptyList()
    private var fillingSpinner = false

    private val speakerListener: (Speaker.State) -> Unit = {
        refreshEngines()
        refreshLang()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvService = findViewById(R.id.tvService)
        tvLang = findViewById(R.id.tvLang)
        spEngine = findViewById(R.id.spEngine)
        spEngineEn = findViewById(R.id.spEngineEn)
        tvRate = findViewById(R.id.tvRate)
        tvPitch = findViewById(R.id.tvPitch)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnTtsSettings).setOnClickListener {
            open(Intent("com.android.settings.TTS_SETTINGS"))
        }
        findViewById<Button>(R.id.btnInstallVoice).setOnClickListener {
            val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            currentEngine()?.let { i.setPackage(it) }
            open(i)
        }

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        findViewById<TextView>(R.id.tvVersion).text = "نسخه $ver"

        setupCloud()
        setupSeekBars()
        setupOcr()
        setupSwitches()

        val et = findViewById<EditText>(R.id.etTest)
        findViewById<Button>(R.id.btnSpeak).setOnClickListener {
            val t = et.text.toString()
            if (t.isBlank()) Toast.makeText(this, "کادر متن خالی است", Toast.LENGTH_SHORT).show()
            else Speaker.speak(t)
        }
        findViewById<Button>(R.id.btnPause).setOnClickListener { Speaker.togglePause() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { Speaker.stop() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { et.setText("") }
        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val clip = clipboardText()
            if (clip.isNullOrBlank()) {
                Toast.makeText(this, "چیزی در کلیپ‌بورد نیست", Toast.LENGTH_SHORT).show()
            } else {
                et.setText(clip)
                Speaker.speak(clip)
            }
        }

        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (fillingSpinner) return
                val pkg = enginePkgs.getOrNull(position) ?: return
                if (pkg != currentEngine()) {
                    Prefs.enginePackage = pkg
                    tvLang.text = "در حال بارگذاری موتور…"
                    Speaker.reload()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spEngineEn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (fillingSpinner) return
                val pkg = enginePkgs.getOrNull(position) ?: return
                if (pkg != Speaker.englishEngine()) {
                    Prefs.englishEnginePackage = pkg
                    Speaker.reload()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        Speaker.addListener(speakerListener)
        Speaker.onWarning = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        refreshService()
        refreshEngines()
        refreshLang()
    }

    override fun onPause() {
        Speaker.removeListener(speakerListener)
        Speaker.onWarning = ReaderService.instance?.let { svc -> { msg: String -> svc.showWarning(msg) } }
        super.onPause()
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "این صفحه روی گوشی شما در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentEngine(): String? = Prefs.enginePackage ?: Speaker.defaultEngine()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, ReaderService::class.java)
        val names = setOf(cn.flattenToString(), cn.flattenToShortString())
        return enabled.split(':').any { it in names }
    }

    private fun refreshService() {
        tvService.text = if (isServiceEnabled() || ReaderService.instance != null)
            "✅ سرویس روشن است. دکمه شناور 🔊 روی صفحه دیده می‌شود."
        else
            "❌ سرویس خاموش است. در تنظیمات دسترسی‌پذیری، «صفحه‌خوان (دکمه شناور)» را روشن کنید."
    }

    private fun refreshEngines() {
        val engines = Speaker.engines()
        if (engines.isEmpty()) return
        val pkgs = engines.map { it.name }
        if (pkgs == enginePkgs && spEngine.adapter != null) return
        enginePkgs = pkgs
        val labels = engines.map { it.label ?: it.name }
        fillingSpinner = true
        spEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spEngineEn.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = enginePkgs.indexOf(currentEngine())
        if (idx >= 0) spEngine.setSelection(idx, false)
        val idxEn = enginePkgs.indexOf(Speaker.englishEngine() ?: currentEngine())
        if (idxEn >= 0) spEngineEn.setSelection(idxEn, false)
        spEngine.post { fillingSpinner = false }
    }

    private fun refreshLang() {
        if (!Speaker.ready) {
            tvLang.text = "در حال آماده‌سازی موتور صدا…"
            return
        }
        tvLang.text = when (Speaker.persianSupport()) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "✅ این موتور صدای فارسی دارد."
            TextToSpeech.LANG_MISSING_DATA -> "⚠️ فارسی پشتیبانی می‌شود ولی داده صدا نصب نیست؛ «نصب داده صدا» را بزنید."
            else -> "❌ این موتور فارسی ندارد. یک موتور TTS فارسی (مثل Sherpa-onnx فارسی یا eSpeak NG) نصب کنید و در «موتور فارسی» انتخابش کنید."
        }
    }

    private fun setupSeekBars() {
        val sbRate = findViewById<SeekBar>(R.id.sbRate)
        val sbPitch = findViewById<SeekBar>(R.id.sbPitch)
        // بازه ۰٫۵ تا ۲٫۰ با گام ۰٫۰۵
        fun toVal(p: Int) = 0.5f + p * 0.05f
        fun toProg(v: Float) = ((v - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

        sbRate.progress = toProg(Prefs.speechRate)
        sbPitch.progress = toProg(Prefs.pitch)
        tvRate.text = "سرعت خواندن: ${fmt(Prefs.speechRate)}"
        tvPitch.text = "زیر و بمی صدا: ${fmt(Prefs.pitch)}"

        sbRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Prefs.speechRate = toVal(p); tvRate.text = "سرعت خواندن: ${fmt(toVal(p))}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Prefs.pitch = toVal(p); tvPitch.text = "زیر و بمی صدا: ${fmt(toVal(p))}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private val cloudVoices = listOf(
        "Kore", "Puck", "Charon", "Zephyr", "Leda", "Aoede", "Callirrhoe", "Autonoe",
        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib",
        "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux",
        "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia",
        "Sadaltager", "Sulafat", "Orus", "Fenrir"
    )

    private fun setupCloud() {
        findViewById<MaterialSwitch>(R.id.swCloud).apply {
            isChecked = Prefs.cloudEnabled
            setOnCheckedChangeListener { _, c -> Prefs.cloudEnabled = c }
        }
        findViewById<EditText>(R.id.etApiKey).apply {
            setText(Prefs.cloudApiKey)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.cloudApiKey = s?.toString() ?: "" }
            })
        }
        findViewById<EditText>(R.id.etModel).apply {
            setText(Prefs.cloudModel)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.cloudModel = s?.toString() ?: "" }
            })
        }
        findViewById<EditText>(R.id.etStyle).apply {
            setText(Prefs.cloudStyle)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.cloudStyle = s?.toString() ?: "" }
            })
        }
        findViewById<Button>(R.id.btnTestCloud).setOnClickListener {
            Toast.makeText(this, "در حال آزمایش…", Toast.LENGTH_SHORT).show()
            CloudTts.test { ok, msg ->
                AlertDialog.Builder(this)
                    .setTitle(if (ok) "اتصال برقرار است" else "خطا")
                    .setMessage(msg)
                    .setPositiveButton("بستن", null)
                    .setNeutralButton("کپی متن خطا") { _: android.content.DialogInterface, _: Int ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("error", msg))
                    }
                    .show()
            }
        }

        findViewById<Spinner>(R.id.spVoice).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, cloudVoices)
            val idx = cloudVoices.indexOf(Prefs.cloudVoice)
            if (idx >= 0) setSelection(idx, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    Prefs.cloudVoice = cloudVoices[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupOcr() {
        val rg = findViewById<RadioGroup>(R.id.rgOcr)
        rg.check(
            when (Prefs.ocrLang) {
                "fas" -> R.id.rbFas
                "eng" -> R.id.rbEng
                else -> R.id.rbFasEng
            }
        )
        rg.setOnCheckedChangeListener { _, id ->
            Prefs.ocrLang = when (id) {
                R.id.rbFas -> "fas"
                R.id.rbEng -> "eng"
                else -> "fas+eng"
            }
        }
    }

    private fun setupSwitches() {
        findViewById<MaterialSwitch>(R.id.swPanel).apply {
            isChecked = Prefs.showPanel
            setOnCheckedChangeListener { _, c ->
                Prefs.showPanel = c
                ReaderService.instance?.setPanelVisible(c)
            }
        }
        findViewById<MaterialSwitch>(R.id.swContinuous).apply {
            isChecked = Prefs.continuous
            setOnCheckedChangeListener { _, c -> Prefs.continuous = c }
        }
        findViewById<MaterialSwitch>(R.id.swSkipButtons).apply {
            isChecked = Prefs.skipButtons
            setOnCheckedChangeListener { _, c -> Prefs.skipButtons = c }
        }
        findViewById<MaterialSwitch>(R.id.swAutoLang).apply {
            isChecked = Prefs.autoLanguage
            setOnCheckedChangeListener { _, c -> Prefs.autoLanguage = c }
        }
    }
}
