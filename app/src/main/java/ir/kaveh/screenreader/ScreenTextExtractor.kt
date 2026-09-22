package ir.kaveh.screenreader

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** متن قابل‌مشاهده روی صفحه را از درخت دسترسی‌پذیری اپلیکیشن جلویی بیرون می‌کشد. */
object ScreenTextExtractor {

    private fun appRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in service.windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == service.packageName) continue
                roots.add(r)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let {
                if (it.packageName?.toString() != service.packageName) roots.add(it)
            }
        }
        return roots
    }

    fun extractLines(service: AccessibilityService, skipButtons: Boolean): List<String> {
        val out = ArrayList<String>()
        for (root in appRoots(service)) walk(root, out, skipButtons, 0)

        // حذف تکرارهای نزدیک به هم (مثلاً متن و توضیح یک عنصر)
        val result = ArrayList<String>()
        for (s in out) {
            val recent = result.takeLast(3)
            if (recent.any { it == s || it.contains(s) }) continue
            result.add(s)
        }
        return result
    }

    private fun isControl(cls: String): Boolean =
        cls.endsWith("Button") || cls.endsWith("ImageView") || cls.endsWith("CheckBox") ||
            cls.endsWith("Switch") || cls.endsWith("Tab") || cls.endsWith("Chip")

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<String>, skipButtons: Boolean, depth: Int) {
        if (n == null || depth > 80) return
        if (!n.isVisibleToUser) return

        val cls = n.className?.toString() ?: ""
        val control = isControl(cls)
        val txt = n.text?.toString()?.trim()

        if (!txt.isNullOrEmpty()) {
            if (!(skipButtons && control && txt.length < 30)) out.add(txt)
        } else {
            // بعضی اپ‌ها (مثل پیام‌رسان‌ها) متن را در contentDescription می‌گذارند
            val cd = n.contentDescription?.toString()?.trim()
            if (!cd.isNullOrEmpty()) {
                val keep = if (skipButtons) (!control && (cd.length >= 12 || n.childCount == 0) && !n.isClickable) || cd.length >= 40
                           else true
                if (keep) out.add(cd)
            }
        }

        for (i in 0 until n.childCount) {
            walk(n.getChild(i), out, skipButtons, depth + 1)
        }
    }

    /** بزرگ‌ترین بخش قابل اسکرول را یک صفحه جلو می‌برد */
    fun scrollForward(service: AccessibilityService): Boolean {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val rect = Rect()

        fun find(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 80) return
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) find(n.getChild(i), depth + 1)
        }

        for (root in appRoots(service)) find(root, 0)
        return best?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }
}
