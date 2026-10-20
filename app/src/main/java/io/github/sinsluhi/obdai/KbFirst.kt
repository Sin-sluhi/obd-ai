package io.github.sinsluhi.obdai

/**
 * Правило владельца «сначала своя база»: разбор собирается из подробной базы кодов (tools/dtc/detail → dtc_ru.json),
 * нейронка подключается, только если в базе чего-то нет. Так разбор есть всегда, мгновенно и без лимитов,
 * а нейронка тратится лишь на редкие коды и незнакомые сочетания.
 */
object KbFirst {

    /**
     * null — база покрывает проверку целиком; иначе строка для журнала: чего именно в базе нет.
     * Покрытие: каждый код разобран подробно (с причинами, проверками и порядком ремонта) — для марки машины или общий,
     * и каждая пара кодов двигателя из разных узлов связана в базе (иначе непонятно, как они влияют друг на друга).
     */
    fun gap(snap: CarSnapshot): String? {
        val codes = snap.allCodes.map { DtcCatalog.base(it) }.distinct()
        if (codes.isEmpty()) return null
        val car = snap.carInfo()
        val bkey = DtcCatalog.brandKey(car.brand)
        val infos = codes.associateWith { DtcCatalog.info(it, bkey) }
        val missing = codes.filter { infos[it]?.detailed != true }
        if (missing.isNotEmpty()) return "коды ${missing.joinToString()}" // i18n-ignore: строка журнала
        val engine = codes.filter { it.startsWith("P") }
        for (i in engine.indices) for (j in i + 1 until engine.size) {
            val a = engine[i]
            val b = engine[j]
            if (infos[a]?.family == infos[b]?.family) continue   // один узел: связь описана в самом семействе
            if (DtcCatalog.links(a, listOf(b), bkey).isEmpty()) return "связка $a + $b" // i18n-ignore: строка журнала
        }
        return null
    }
}
