package ir.kaveh.screenreader

object TextTools {
    private val latinRe = Regex("[A-Za-z]")
    private val persianRe = Regex("[\\u0600-\\u06FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val urlRe = Regex("(https?://|www\\.)\\S+")
    private val spacesRe = Regex("[\\t\\u00A0 ]+")
    private val sentenceEnd = charArrayOf('.', '!', '?', '؟', '؛', ':', '…')

    /** یکسان‌سازی حروف عربی/فارسی، حذف لینک‌ها و فاصله‌های اضافه */
    fun normalize(s: String): String = s
        .replace('ي', 'ی').replace('ك', 'ک').replace('\u0649', 'ی')
        .replace(urlRe, " ")
        .replace(spacesRe, " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()

    fun isMostlyLatin(s: String): Boolean {
        val l = latinRe.findAll(s).count()
        val p = persianRe.findAll(s).count()
        return l > 3 && l > p * 2
    }

    /** کلید مقایسه خطوط برای حذف تکرار بین صفحه‌ها */
    fun key(line: String): String = line.filterNot { it.isWhitespace() }

    /**
     * متن OCR: خط‌های شکسته داخل یک پاراگراف را به هم وصل می‌کند
     * (مهم برای کتاب و PDF که هر خط وسط جمله می‌شکند).
     */
    fun joinOcrLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) { sb.append('\n'); continue }
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
            sb.append(line)
        }
        return sb.toString()
    }

    /** تقسیم متن به تکه‌های کوتاه (جمله‌محور) برای TTS */
    fun chunk(text: String, maxLen: Int = 350): List<String> {
        val pieces = text.split(Regex("(?<=[.!?؟؛…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }

        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out.add(sb.toString().trim())
            sb.setLength(0)
        }

        for (p in pieces) {
            // عنوان‌ها و خط‌های بدون علامت پایان جمله را با نقطه جدا کن تا TTS مکث کند
            var piece = if (p.last() in sentenceEnd) p else "$p."

            while (piece.length > maxLen) {
                var cut = piece.lastIndexOfAny(charArrayOf(' ', '،', ','), maxLen)
                if (cut < maxLen / 2) cut = maxLen
                flush()
                out.add(piece.substring(0, cut).trim())
                piece = piece.substring(cut).trim()
            }
            if (piece.isEmpty()) continue

            if (sb.isNotEmpty() &&
                (sb.length + piece.length + 1 > maxLen ||
                    isMostlyLatin(sb.toString()) != isMostlyLatin(piece))
            ) flush()

            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(piece)
        }
        flush()
        return out
    }
}
