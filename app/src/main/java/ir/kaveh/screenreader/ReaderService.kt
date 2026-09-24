package ir.kaveh.screenreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class ReaderService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ReaderService? = null
            private set
    }

    private enum class Source { SCREEN, OCR }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var pauseBtn: TextView? = null
    private var menu: LinearLayout? = null

    private var lastSource = Source.SCREEN
    private var continuousActive = false
    private var lastKeys: Set<String> = emptySet()
    private var emptyPages = 0
    private var busy = false

    private val stateListener: (Speaker.State) -> Unit = { updateIcons() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Speaker.addListener(stateListener)
        Speaker.onFinished = { onSpeechFinished() }
        Speaker.onWarning = { showWarning(it) }
        if (Prefs.showPanel) showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        continuousActive = false
        Speaker.stop()
    }

    override fun onDestroy() {
        continuousActive = false
        Speaker.stop()
        Speaker.removeListener(stateListener)
        Speaker.onFinished = null
        Speaker.onWarning = null
        removePanel()
        instance = null
        super.onDestroy()
    }

    // ───────────────────────── پنل شناور ─────────────────────────

    fun setPanelVisible(visible: Boolean) {
        if (visible) showPanel() else removePanel()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun makeButton(label: String, sizeDp: Int, color: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = if (sizeDp >= 56) 24f else 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(color)
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                topMargin = dp(6)
            }
            setOnClickListener { action() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panel != null) return
        val brand = Color.parseColor("#E61E6B5C")
        val dark = Color.parseColor("#E6333333")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val b = makeButton("🔊", 56, brand) { toggleMenu() }
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        m.addView(makeButton("📖", 46, dark) { collapse(); startReadScreen() })
        m.addView(makeButton("🔍", 46, dark) { collapse(); startReadOcr() })
        m.addView(makeButton("📋", 46, dark) { collapse(); readClipboard() })
        val p = makeButton("⏸", 46, dark) { Speaker.togglePause() }
        m.addView(p)
        m.addView(makeButton("⏪", 46, dark) { Speaker.skip(-1) })
        m.addView(makeButton("⏩", 46, dark) { Speaker.skip(1) })
        m.addView(makeButton("⏭", 46, dark) { collapse(); nextPage() })
        m.addView(makeButton("⏹", 46, dark) { stopAll() })

        root.addView(b)
        root.addView(m)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.panelX
            y = Prefs.panelY
        }

        // کشیدن دکمه اصلی برای جابه‌جایی
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { Prefs.panelX = lp.x; Prefs.panelY = lp.y } else v.performClick()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, lp)
            panel = root; params = lp; bubble = b; pauseBtn = p; menu = m
            updateIcons()
        } catch (e: Exception) {
            toast("نمایش دکمه شناور ممکن نشد")
        }
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; bubble = null; pauseBtn = null; menu = null; params = null
    }

    private fun toggleMenu() {
        val m = menu ?: return
        m.visibility = if (m.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun collapse() { menu?.visibility = View.GONE }

    private fun updateIcons() {
        when (Speaker.state) {
            Speaker.State.SPEAKING -> { bubble?.text = "🗣"; pauseBtn?.text = "⏸" }
            Speaker.State.PAUSED -> { bubble?.text = "⏸"; pauseBtn?.text = "▶" }
            Speaker.State.IDLE -> { bubble?.text = if (busy) "⏳" else "🔊"; pauseBtn?.text = "⏸" }
        }
    }

    private fun setBusy(b: Boolean) { busy = b; updateIcons() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun showWarning(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ───────────────────────── خواندن ─────────────────────────

    private fun stopAll() {
        continuousActive = false
        Speaker.stop()
        collapse()
    }

    private fun startReadScreen() {
        lastSource = Source.SCREEN
        continuousActive = Prefs.continuous
        emptyPages = 0
        readScreen(onlyNew = false)
    }

    private fun startReadOcr() {
        lastSource = Source.OCR
        continuousActive = Prefs.continuous
        emptyPages = 0
        readOcr(onlyNew = false)
    }

    private fun readScreen(onlyNew: Boolean) {
        val lines = ScreenTextExtractor.extractLines(this, Prefs.skipButtons)
        handleLines(lines, onlyNew, joinForOcr = false)
        if (lines.isEmpty() && !onlyNew) toast("متنی پیدا نشد؛ دکمه 🔍 (تشخیص از تصویر) را امتحان کنید")
    }

    private fun readOcr(onlyNew: Boolean) {
        if (busy) return
        setBusy(true)
        panel?.visibility = View.INVISIBLE
        // کمی صبر تا دکمه شناور از تصویر صفحه حذف شود
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    panel?.visibility = View.VISIBLE
                    val hw = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?.let { wrapped -> wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() } }
                    } catch (_: Exception) { null } finally { hw.close() }
                    if (bmp == null) { setBusy(false); toast("گرفتن تصویر صفحه ناموفق بود"); return }

                    val cropped = cropBars(bmp)
                    if (!onlyNew) toast("در حال تشخیص متن…")
                    OcrEngine.recognize(this@ReaderService, cropped, Prefs.ocrLang) { lines ->
                        setBusy(false)
                        if (lines == null) {
                            toast("خطا در تشخیص متن")
                            continuousActive = false
                            return@recognize
                        }
                        handleLines(lines, onlyNew, joinForOcr = true)
                        if (lines.none { it.isNotBlank() } && !onlyNew) toast("متنی در تصویر پیدا نشد")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    panel?.visibility = View.VISIBLE
                    setBusy(false)
                    toast("گرفتن تصویر صفحه ممکن نشد (کد $errorCode)")
                }
            })
        }, 300)
    }

    /** نوار وضعیت بالا و نوار ناوبری پایین را از تصویر حذف می‌کند */
    private fun cropBars(src: Bitmap): Bitmap {
        fun dim(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        val top = dim("status_bar_height").coerceAtMost(src.height / 5)
        val bottom = dim("navigation_bar_height").coerceAtMost(src.height / 5)
        val h = src.height - top - bottom
        if (h <= 0 || (top == 0 && bottom == 0)) return src
        val out = Bitmap.createBitmap(src, 0, top, src.width, h)
        if (out != src) src.recycle()
        return out
    }

    private fun handleLines(lines: List<String>, onlyNew: Boolean, joinForOcr: Boolean) {
        val nonEmpty = lines.filter { it.isNotBlank() }
        val fresh = if (onlyNew) {
            // در OCR خط‌های خالی (مرز پاراگراف) نگه داشته می‌شوند
            lines.filter { it.isBlank() || TextTools.key(it) !in lastKeys }
        } else lines
        lastKeys = nonEmpty.map { TextTools.key(it) }.toSet()

        if (fresh.none { it.isNotBlank() }) {
            if (onlyNew && continuousActive) {
                emptyPages++
                if (emptyPages < 2) advanceAndRead()
                else { continuousActive = false; toast("به انتهای صفحه رسیدیم") }
            }
            return
        }
        emptyPages = 0
        val text = if (joinForOcr) TextTools.joinOcrLines(fresh) else fresh.joinToString("\n")
        Speaker.speak(text)
    }

    private fun onSpeechFinished() {
        if (continuousActive) advanceAndRead()
    }

    private fun nextPage() {
        Speaker.stop()
        emptyPages = 0
        continuousActive = Prefs.continuous
        advanceAndRead()
    }

    /** یک صفحه جلو برود و فقط متن جدید را بخواند */
    private fun advanceAndRead() {
        if (!ScreenTextExtractor.scrollForward(this)) swipeUp()
        main.postDelayed({
            if (lastSource == Source.SCREEN) readScreen(onlyNew = true) else readOcr(onlyNew = true)
        }, 1100)
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.80f)
            lineTo(x, dm.heightPixels * 0.22f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * خواندن متن کپی‌شده. از اندروید ۱۰ به بعد فقط برنامه‌ای که فوکوس دارد می‌تواند
     * کلیپ‌بورد را بخواند، برای همین یک پنجره نامرئی فوکوس‌دار لحظه‌ای باز می‌شود.
     */
    private fun readClipboard() {
        val focus = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            wm.addView(focus, lp)
        } catch (_: Exception) {
        }

        main.postDelayed({
            val text = try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0)
                    clip.getItemAt(0).coerceToText(this).toString() else ""
            } catch (e: Exception) {
                ""
            }
            try { wm.removeView(focus) } catch (_: Exception) {}

            if (text.isBlank()) toast("چیزی در کلیپ‌بورد نیست")
            else {
                continuousActive = false
                Speaker.speak(text)
            }
        }, 250)
    }

    /** برای منوی «بلند بخوان» */
    fun readText(text: String) {
        continuousActive = false
        Speaker.speak(text)
    }
}
