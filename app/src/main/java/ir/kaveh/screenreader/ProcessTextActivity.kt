package ir.kaveh.screenreader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** «بلند بخوان» در منوی انتخاب متن هر اپ، و گزینه اشتراک‌گذاری متن */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            val svc = ReaderService.instance
            if (svc != null) svc.readText(text) else Speaker.speak(text)
            Toast.makeText(this, "در حال خواندن…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
