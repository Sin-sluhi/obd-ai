package io.github.sinsluhi.obdai

/**
 * Base64 без java.util.Base64 (его нет до Android 8) и без android.util.Base64 (на стенде JVM он заглушка).
 * Обычный алфавит с «=» и URL-алфавит (- _), декодер принимает оба и не требует выравнивания.
 */
object B64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(STD[b0 shr 2])
            sb.append(STD[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else STD[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else STD[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in text) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> continue   // «=», переносы и пробелы пропускаем
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
