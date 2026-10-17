package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Стикеры (крупные эмодзи без пузыря) и живые ветки по статистике сервера. */
class ForumTest {

    @Test fun stickerIsEmojiOnly() {
        assertTrue(Stickers.isSticker("🚗💨"))
        assertTrue(Stickers.isSticker("👍"))
        assertTrue(Stickers.isSticker("🛠️"))      // с селектором варианта
        assertTrue(Stickers.isSticker("🏎️ "))
        assertTrue(Stickers.isSticker("👍🏽"))      // с тоном кожи
        assertFalse(Stickers.isSticker("привет"))
        assertFalse(Stickers.isSticker("ок 👍"))
        assertFalse(Stickers.isSticker(""))
        assertFalse(Stickers.isSticker("🚗🚗🚗🚗🚗"))   // пять — уже текст
        assertFalse(Stickers.isSticker("12"))
    }

    @Test fun everyBuiltInStickerCountsAsSticker() {
        for (s in Stickers.stickers) assertTrue(s, Stickers.isSticker(s))
        for (e in Stickers.emoji) assertTrue(e, Stickers.isSticker(e))
    }

    @Test fun liveRoomsMappedToTreeNewestFirst() {
        val g1 = ForumGen("renault/arkana/1", "I (LJL)", "2019–2022")
        val g2 = ForumGen("lada/vesta/1", "I", "2015–2022")
        val tree = listOf(
            ForumBrand("Renault", "renault", listOf(ForumModel("Arkana", "arkana", listOf(g1)))),
            ForumBrand("Lada", "lada", listOf(ForumModel("Vesta", "vesta", listOf(g2))))
        )
        val rooms = mapOf(
            g1.id to RoomStat(g1.id, 3, 2, 1000L, "Водитель-1", "привет"),
            g2.id to RoomStat(g2.id, 1, 0, 5000L, "Водитель-2", "ок"),
            "unknown/room" to RoomStat("unknown/room", 9, 9, 9000L, "x", "y")
        )
        val live = ForumTree.liveRooms(tree, rooms)
        assertEquals(listOf(g2.id, g1.id), live.map { it.first.room })
        assertEquals("Renault", live[1].second.first.name)
        assertEquals("Arkana", live[1].second.second.name)
    }
}
