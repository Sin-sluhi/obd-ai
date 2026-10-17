package io.github.sinsluhi.obdai

/** Пункт паспорта адаптера: что проверяли, прошёл ли (null — проверка идёт), подробность («42 мс»). */
data class AdapterTest(val key: String, val ok: Boolean? = null, val detail: String = "") {
    val title: String get() = tr("adp_$key")
}

/**
 * Паспорт адаптера по поведению, а не по строке версии: «ELM327 v1.5» впечатывают в любую прошивку, а вот
 * врать на команды клон не умеет. Каждый пункт — команда и ожидаемый ответ по даташиту ELM327 v1.4b:
 * честная прошивка отвечает «?» на незнакомое, настоящий набор команд знает AT@2 и ATPPS, расширенная адресация,
 * ручной поток, фильтры и сырой режим либо есть, либо нет. Итог — не ярлык «клон», а список того, что адаптер
 * сможет и чего не сможет. Чистый Kotlin: гоняется на стенде.
 */
class AdapterProbe(private val send: (String, Long) -> String) {

    companion object {
        val KEYS = listOf("honest", "identity", "speed", "cea", "flow", "filter", "cra", "raw", "forget")

        /** Паспорт демо-адаптера: всё зелёное, чтобы демо показывало карточку целиком. */
        fun demo(): List<AdapterTest> = KEYS.map { AdapterTest(it, true, if (it == "speed") tr("adp_ms", 38) else "") }
    }

    /** Прогоняет проверки по очереди; [onStep] получает текущий список после каждого шага — карточка оживает по ходу. */
    fun execute(onStep: (List<AdapterTest>) -> Unit = {}): List<AdapterTest> {
        val out = mutableListOf<AdapterTest>()
        fun step(key: String, body: () -> AdapterTest) {
            out.add(AdapterTest(key)); onStep(out.toList())
            out[out.size - 1] = runCatching(body).getOrElse { AdapterTest(key, false) }
            onStep(out.toList())
        }
        fun q(cmd: String) = send(cmd, 3000).uppercase()
        fun supported(cmd: String) = !q(cmd).contains("?")

        step("honest") {
            // несуществующая команда: честная прошивка отвечает «?», «клякса» — «OK» на всё, и её «OK» ничего не значит
            val r = q("ATXYZ")
            AdapterTest("honest", r.contains("?") && !r.contains("OK"))
        }
        step("identity") {
            val id = q("AT@2")
            val pps = q("ATPPS")
            val idOk = !id.contains("?") && id.isNotBlank()
            val ppsOk = !pps.contains("?") && pps.contains(":")
            AdapterTest("identity", idOk || ppsOk, if (idOk) id.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(24) else "")
        }
        step("speed") {
            val t0 = System.currentTimeMillis()
            repeat(3) { q("ATI") }
            val ms = (System.currentTimeMillis() - t0) / 3
            AdapterTest("speed", ms < 400, tr("adp_ms", ms))
        }
        step("cea") { val ok = supported("ATCEA 12"); q("ATCEA"); AdapterTest("cea", ok) }
        step("flow") {
            // порядок как в даташите: FCSM 1 принимается только после FCSH и FCSD
            q("ATFCSH 7E0"); q("ATFCSD 30 00 00")
            val ok = supported("ATFCSM 1"); q("ATFCSM 0")
            AdapterTest("flow", ok)
        }
        step("filter") { val ok = supported("ATCF 700") && supported("ATCM 700"); q("ATAR"); AdapterTest("filter", ok) }
        step("cra") { val ok = supported("ATCRA 7E8"); q("ATAR"); AdapterTest("cra", ok) }
        step("raw") { val ok = supported("ATCAF0") && supported("ATAL"); q("ATCAF1"); AdapterTest("raw", ok) }
        step("forget") { AdapterTest("forget", supported("ATFE")) }
        return out
    }
}
