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
        appendLine("OBD AI, проверка перед покупкой: $title")
        if (car.isNotBlank()) appendLine(car)
        declaredKm?.let { appendLine("Пробег по словам продавца: $it км") }
        appendLine(text)
        if (reasons.isNotEmpty()) { appendLine(); appendLine("Что нашли:"); reasons.forEach { appendLine("• ${it.text}") } }
        if (bargain.isNotEmpty()) {
            appendLine(); appendLine("Аргументы для торга:")
            bargain.forEach { appendLine("• ${it.what}: ${formatPrice(it.priceFrom)}") }
            appendLine("Итого: ${formatPrice(bargainTotal)}")
        }
        if (checks.isNotEmpty()) { appendLine(); appendLine("Проверить на тест-драйве:"); checks.forEach { appendLine("• $it") } }
    }
}

object Purchase {
    private val CRASH_CODES = setOf("B00D0", "B1650")

    fun build(snap: CarSnapshot, car: VinDecoder.Info, declaredKm: Int?, hint: String? = null): PurchaseReport {
        val bkey = DtcCatalog.brandKey(car.brand)
        val reasons = ArrayList<Flag>()
        val bargain = ArrayList<BargainItem>()

        // 1. что помнят блоки
        val active = snap.allCodes.filter { it.contains("(активная)") || (!it.contains("(") && snap.stored.contains(it)) }
        val archive = snap.allCodes.filter { it.contains("(история)") }
        val pending = snap.allCodes.filter { it.contains("(неподтверждённая)") || (!it.contains("(") && snap.pending.contains(it) && !snap.stored.contains(it)) }
        var danger = false
        for (raw in snap.allCodes) {
            val code = DtcCatalog.base(raw)
            if (code in CRASH_CODES) { reasons.add(Flag("danger", "Блок подушек помнит срабатывание: машина била подушки, блок не заменён или сброшен кустарно.")); danger = true }
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
                stop || sev == "high" -> { danger = true; reasons.add(Flag("danger", "$code — $title, активна сейчас" + (issue?.let { ". ${it.title}" } ?: "") + ".")) }
                else -> reasons.add(Flag("warning", "$code — $title, активна сейчас."))
            }
            if (price > 0) bargain.add(BargainItem("$code $title", price))
        }
        if (archive.isNotEmpty()) {
            val names = archive.take(4).joinToString { c -> DtcCatalog.base(c) }
            reasons.add(Flag("info", "В истории блоков ${archive.size} код(а): $names. Сейчас не активны, но за ними стоит следить."))
            archive.forEach { raw ->
                val code = DtcCatalog.base(raw)
                val info = DtcCatalog.info(code, bkey)
                val issue = KnownIssues.find(car, snap.vin, code)
                if (issue != null && issue.severity == "high") reasons.add(Flag("warning", "$code в истории — известная болячка модели: ${issue.title}."))
                val price = issue?.price?.takeIf { it > 0 } ?: 0
                if (price > 0 && info != null) bargain.add(BargainItem("$code ${info.title} (в истории, известная проблема модели)", price / 2))
            }
        }
        if (pending.isNotEmpty()) reasons.add(Flag("info", "Неподтверждённые коды: ${pending.joinToString { DtcCatalog.base(it) }}. Блок заметил сбой один раз."))

        // 2. следы подготовки к продаже и чужие блоки (флаги проверки уже посчитаны)
        snap.flags.forEach { f ->
            if (f.level == "danger") danger = true
            if (!f.text.startsWith("Пробег по данным ЭБУ")) reasons.add(f)
        }

        // 3. пробег: ЭБУ против слов продавца
        val odo = snap.stats.odometerKm
        if (odo != null) {
            if (declaredKm != null && declaredKm > 0) {
                val diff = odo - declaredKm
                when {
                    diff > declaredKm * 0.15 && diff > 10_000 -> { danger = true; reasons.add(Flag("danger", "Блок двигателя насчитал %.0f км, продавец говорит %d км: пробег скручен минимум на %.0f км.".format(odo, declaredKm, diff))) }
                    diff < -declaredKm * 0.15 && -diff > 10_000 -> reasons.add(Flag("warning", "Блок двигателя насчитал %.0f км — меньше заявленных %d. Блок меняли или обнуляли.".format(odo, declaredKm)))
                    else -> reasons.add(Flag("info", "Пробег по ЭБУ %.0f км совпадает с заявленным (%d км).".format(odo, declaredKm)))
                }
            } else reasons.add(Flag("info", "Пробег по данным ЭБУ: %.0f км. Введите пробег по словам продавца, чтобы сверить.".format(odo)))
        } else reasons.add(Flag("info", "Пробег из блока двигателя эта машина не отдаёт: сверяйте по сервисной книжке, шинам, педалям и рулю."))

        // 4. аккумулятор и адаптер
        snap.battery?.let { b -> if (b.level == "danger" || b.level == "warning") { reasons.add(Flag("warning", "Аккумулятор/зарядка: ${b.title}.")); bargain.add(BargainItem("Аккумулятор", 6000)) } }

        // 5. болячки модели — что проверить на тест-драйве
        val checks = ArrayList<String>()
        Kb.modelIssues(car, hint ?: snap.carHint)?.let { k ->
            k.causes.take(6).forEach { checks.add(it) }
        }
        KnownIssues.forCar(car, snap.vin).take(6).forEach { i ->
            val first = i.note.substringBefore(". ").let { if (it.length > 160) it.take(157) + "…" else it }
            checks.add(i.title + (if (i.mileage.isNotBlank()) " (${i.mileage})" else "") + ": " + first + ".")
        }
        checks.add("Холодный пуск: попросите не прогревать машину до вашего приезда; стук первые секунды — фазовращатели или вкладыши.")
        checks.add("После тест-драйва запустите проверку ещё раз: коды, которые появятся после поездки, продавец стёр перед встречей.")

        val verdict = when {
            danger -> "run"
            reasons.any { it.level == "warning" } || bargain.isNotEmpty() -> "bargain"
            else -> "buy"
        }
        val total = bargain.sumOf { it.priceFrom }
        val title = when (verdict) { "run" -> "Лучше не брать"; "bargain" -> "Брать с торгом"; else -> "Можно брать" }
        val text = when (verdict) {
            "run" -> "Есть признаки, из-за которых машину не стоит покупать без глубокой проверки в сервисе: " +
                reasons.filter { it.level == "danger" }.joinToString(" ") { it.text }
            "bargain" -> "Серьёзных стоп-сигналов нет, но есть за что торговаться" +
                (if (total > 0) ": ремонт по найденному ${formatPrice(total)}" else "") + ". Смотрите список и проверьте болячки модели на тест-драйве."
            else -> "Блоки чистые, следов подготовки к продаже нет, пробег по ЭБУ не спорит с продавцом. Осталось проверить кузов и тест-драйв."
        }
        return PurchaseReport(verdict, title, text, reasons, bargain, checks)
    }
}
