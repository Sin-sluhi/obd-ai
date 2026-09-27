package io.github.sinsluhi.obdai

/**
 * «Перед покупкой»: та же проверка, но вывод для покупателя — брать, торговаться или бежать.
 * Только по измерениям: коды и их статус, недавние стирания, чужой VIN в блоках, пробег ЭБУ против слов продавца,
 * аккумулятор, известные болячки модели как список, что проверить на тест-драйве, и аргументы для торга с ценами «от».
 */
data class BargainItem(val what: String, val priceFrom: Int)

data class PurchaseReport(
    val verdict: String,          // buy / bargain / run
    val title: String,
    val text: String,
    val reasons: List<Flag>,      // danger / warning / info
    val bargain: List<BargainItem>,
    val checks: List<String>      // что проверить на тест-драйве
) {
    val bargainTotal: Int get() = bargain.sumOf { it.priceFrom }

    fun shareText(car: String, declaredKm: Int?): String = buildString {
        appendLine(tr("buy_share_title", title))
        if (car.isNotBlank()) appendLine(car)
        declaredKm?.let { appendLine(tr("buy_share_declared", it)) }
        appendLine(text)
        if (reasons.isNotEmpty()) { appendLine(); appendLine(tr("buy_share_found")); reasons.forEach { appendLine("• ${it.text}") } }
        if (bargain.isNotEmpty()) {
            appendLine(); appendLine(tr("buy_share_bargain"))
            bargain.forEach { appendLine("• ${it.what}: ${formatPrice(it.priceFrom)}") }
            appendLine(tr("buy_share_total", formatPrice(bargainTotal)))
        }
        if (checks.isNotEmpty()) { appendLine(); appendLine(tr("buy_share_checks")); checks.forEach { appendLine("• $it") } }
    }
}

object Purchase {
    private val CRASH_CODES = setOf("B00D0", "B1650")

    fun build(snap: CarSnapshot, car: VinDecoder.Info, declaredKm: Int?, hint: String? = null): PurchaseReport {
        val bkey = DtcCatalog.brandKey(car.brand)
        val reasons = ArrayList<Flag>()
        val bargain = ArrayList<BargainItem>()

        // 1. что помнят блоки (суффиксы статуса приходят из снимка, это разбор данных, не текст интерфейса)
        val active = snap.allCodes.filter { it.contains("(активная)") || (!it.contains("(") && snap.stored.contains(it)) } // i18n-ignore
        val archive = snap.allCodes.filter { it.contains("(история)") } // i18n-ignore
        val pending = snap.allCodes.filter { it.contains("(неподтверждённая)") || (!it.contains("(") && snap.pending.contains(it) && !snap.stored.contains(it)) } // i18n-ignore
        var danger = false
        for (raw in snap.allCodes) {
            val code = DtcCatalog.base(raw)
            if (code in CRASH_CODES) { reasons.add(Flag("danger", tr("buy_airbag_fired"))); danger = true }
        }
        active.forEach { raw ->
            val code = DtcCatalog.base(raw)
            val info = DtcCatalog.info(code, bkey) ?: DtcCatalog.genericInfo(code)
            val issue = KnownIssues.find(car, snap.vin, code)
            val title = info?.title ?: DtcCatalog.title(code)
            val sev = issue?.severity ?: info?.severity ?: "medium"
            val stop = info?.stop == true
            val price = issue?.price?.takeIf { it > 0 } ?: info?.priceFrom ?: 0
            when {
                stop || sev == "high" -> { danger = true; reasons.add(Flag("danger", tr("buy_code_active_now", code, title) + (issue?.let { ". ${it.title}" } ?: "") + ".")) }
                else -> reasons.add(Flag("warning", tr("buy_code_active_now", code, title) + "."))
            }
            if (price > 0) bargain.add(BargainItem("$code $title", price))
        }
        if (archive.isNotEmpty()) {
            val names = archive.take(4).joinToString { c -> DtcCatalog.base(c) }
            reasons.add(Flag("info", trPlural("buy_archive_codes", archive.size, names)))
            archive.forEach { raw ->
                val code = DtcCatalog.base(raw)
                val info = DtcCatalog.info(code, bkey)
                val issue = KnownIssues.find(car, snap.vin, code)
                if (issue != null && issue.severity == "high") reasons.add(Flag("warning", tr("buy_archive_known_issue", code, issue.title)))
                val price = issue?.price?.takeIf { it > 0 } ?: 0
                if (price > 0 && info != null) bargain.add(BargainItem(tr("buy_bargain_archive_item", code, info.title), price / 2))
            }
        }
        if (pending.isNotEmpty()) reasons.add(Flag("info", tr("buy_pending_codes", pending.joinToString { DtcCatalog.base(it) })))

        // 2. следы подготовки к продаже и чужие блоки (флаги проверки уже посчитаны)
        snap.flags.forEach { f ->
            if (f.level == "danger") danger = true
            if (!f.text.startsWith("Пробег по данным ЭБУ")) reasons.add(f) // i18n-ignore
        }

        // 3. пробег: ЭБУ против слов продавца
        val odo = snap.stats.odometerKm
        if (odo != null) {
            val odoText = "%.0f".format(odo)
            if (declaredKm != null && declaredKm > 0) {
                val diff = odo - declaredKm
                when {
                    diff > declaredKm * 0.15 && diff > 10_000 -> { danger = true; reasons.add(Flag("danger", tr("buy_odo_rolled", odoText, declaredKm, "%.0f".format(diff)))) }
                    diff < -declaredKm * 0.15 && -diff > 10_000 -> reasons.add(Flag("warning", tr("buy_odo_less", odoText, declaredKm)))
                    else -> reasons.add(Flag("info", tr("buy_odo_match", odoText, declaredKm)))
                }
            } else reasons.add(Flag("info", tr("buy_odo_enter", odoText)))
        } else reasons.add(Flag("info", tr("buy_odo_none")))

        // 4. аккумулятор и адаптер
        snap.battery?.let { b -> if (b.level == "danger" || b.level == "warning") { reasons.add(Flag("warning", tr("buy_battery", b.title))); bargain.add(BargainItem(tr("buy_battery_item"), 6000)) } }

        // 5. болячки модели — что проверить на тест-драйве
        val checks = ArrayList<String>()
        Kb.modelIssues(car, hint ?: snap.carHint)?.let { k ->
            k.causes.take(6).forEach { checks.add(it) }
        }
        KnownIssues.forCar(car, snap.vin).take(6).forEach { i ->
            val first = i.note.substringBefore(". ").let { if (it.length > 160) it.take(157) + "…" else it }
            checks.add(i.title + (if (i.mileage.isNotBlank()) " (${i.mileage})" else "") + ": " + first + ".")
        }
        checks.add(tr("buy_check_cold_start"))
        checks.add(tr("buy_check_recheck"))

        val verdict = when {
            danger -> "run"
            reasons.any { it.level == "warning" } || bargain.isNotEmpty() -> "bargain"
            else -> "buy"
        }
        val total = bargain.sumOf { it.priceFrom }
        val title = when (verdict) { "run" -> tr("buy_title_run"); "bargain" -> tr("buy_title_bargain"); else -> tr("buy_title_buy") }
        val text = when (verdict) {
            "run" -> tr("buy_text_run", reasons.filter { it.level == "danger" }.joinToString(" ") { it.text })
            "bargain" -> if (total > 0) tr("buy_text_bargain_priced", formatPrice(total)) else tr("buy_text_bargain")
            else -> tr("buy_text_buy")
        }
        return PurchaseReport(verdict, title, text, reasons, bargain, checks)
    }
}
