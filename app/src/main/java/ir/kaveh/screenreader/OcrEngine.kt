package ir.kaveh.screenreader

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Executors

/** تشخیص متن آفلاین با Tesseract (فارسی + انگلیسی) */
object OcrEngine {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tess: TessBaseAPI? = null
    private var loadedLang: String? = null

    /** فایل‌های زبان را یک‌بار از assets به حافظه داخلی کپی می‌کند */
    private fun dataDir(ctx: Context): File {
        val base = File(ctx.filesDir, "tesseract")
        val dir = File(base, "tessdata")
        dir.mkdirs()
        val names = ctx.assets.list("tessdata") ?: emptyArray()
        for (name in names) {
            val dst = File(dir, name)
            if (dst.exists() && dst.length() > 0) continue
            val tmp = File(dir, "$name.tmp")
            ctx.assets.open("tessdata/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(dst)
        }
        return base
    }

    /** نتیجه: فهرست خطوط (خط خالی = مرز پاراگراف) یا null در صورت خطا */
    fun recognize(ctx: Context, bitmap: Bitmap, lang: String, callback: (List<String>?) -> Unit) {
        exec.execute {
            val result: List<String>? = try {
                val base = dataDir(ctx)
                var t = tess
                if (t == null || loadedLang != lang) {
                    t?.recycle()
                    t = TessBaseAPI()
                    if (!t.init(base.absolutePath, lang)) {
                        t.recycle()
                        tess = null
                        loadedLang = null
                        throw IllegalStateException("Tesseract init failed")
                    }
                    t.setPageSegMode(3) // PSM_AUTO: تشخیص خودکار بلوک‌ها و ستون‌ها
                    tess = t
                    loadedLang = lang
                }
                t.setImage(bitmap)
                val text = t.getUTF8Text() ?: ""
                t.clear()
                text.split('\n').map { it.trim() }
            } catch (e: Throwable) {
                null
            } finally {
                bitmap.recycle()
            }
            main.post { callback(result) }
        }
    }
}
