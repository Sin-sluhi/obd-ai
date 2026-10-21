package io.github.sinsluhi.obdai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** Свой Base64 (для Android 7) обязан совпадать со стандартным java.util.Base64 на любых данных. */
class B64Test {
    @Test
    fun matchesJavaBase64() {
        val rnd = Random(42)
        for (n in 0..300) {
            val data = ByteArray(n) { rnd.nextInt(256).toByte() }
            val std = Base64.getEncoder().encodeToString(data)
            assertEquals("encode n=$n", std, B64.encode(data))
            assertArrayEquals("decode n=$n", data, B64.decode(std))
            // URL-алфавит без выравнивания (ссылки Bing в ForumSearch)
            val url = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
            assertArrayEquals("decode url n=$n", data, B64.decode(url))
        }
    }

    @Test
    fun knownValues() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertEquals("Zg==", B64.encode("f".toByteArray()))
        assertEquals("Zm9vYmFy", B64.encode("foobar".toByteArray()))
        assertEquals("foobar", String(B64.decode("Zm9vYmFy")))
    }
}
