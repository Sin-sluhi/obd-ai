package io.github.sinsluhi.obdai

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Ключи нейронок, вшитые в сборку. Решение владельца (22.10.2026): ключи остаются в APK, но не лежат в нём открытым
 * текстом — `app/build.gradle.kts` кладёт в BuildConfig не ключ, а ключ XOR маска (hex), строка `gsk_…` поиском по
 * APK не находится. Это защита от «поиска по файлу», не от того, кто готов отлаживать приложение: настоящий секрет
 * может жить только на сервере.
 */
object Secrets {
    // та же маска, что в app/build.gradle.kts (xorHex); менять только вместе
    private val MASK = intArrayOf(0x5A, 0x13, 0xC7, 0x2E, 0x91, 0x6B, 0xF4, 0x08, 0x3D, 0xA6, 0x77, 0xE2, 0x19, 0xB5, 0x4C, 0x80)

    private fun unmask(hex: String): String {
        if (hex.isBlank() || hex.length % 2 != 0) return ""
        val out = CharArray(hex.length / 2)
        for (i in out.indices) {
            val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return ""
            out[i] = (b xor MASK[i % MASK.size]).toChar()
        }
        return String(out)
    }

    /**
     * Подписан ли установленный APK нашим постоянным ключом. Перепакованная «Васей» сборка (свой ключ подписи) ключей
     * нейронок не получает: вытащить их, просто пересобрав APK с логами, не выйдет. Отладочные сборки без постоянного
     * ключа (эмулятор android7.yml, форки) тоже работают без ключей — разбор идёт по своей базе.
     */
    @Volatile private var genuine = false

    fun init(context: Context) {
        genuine = runCatching { signerSha256(context) }.getOrDefault(emptySet()).contains(BuildConfig.SIGN_SHA256)
        if (!genuine) Trace.note("подпись APK не наша — ключи нейронок отключены") // i18n-ignore: лог
    }

    @Suppress("DEPRECATION")
    private fun signerSha256(context: Context): Set<String> {
        val pm = context.packageManager
        val name = context.packageName
        val certs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures ?: return emptySet()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return certs.map { c -> md.digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    val groqKey: String get() = if (genuine) groq else ""
    val openRouterKey: String get() = if (genuine) openRouter else ""

    private val groq: String by lazy { unmask(BuildConfig.AI_API_KEY_X) }
    private val openRouter: String by lazy { unmask(BuildConfig.OPENROUTER_KEY_X) }
}
