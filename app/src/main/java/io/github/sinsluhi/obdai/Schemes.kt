package io.github.sinsluhi.obdai

/**
 * Принципиальные схемы типовых узлов: какие провода у датчика или исполнителя, куда идут и что на них мерить.
 * Общие для большинства машин значения (не распиновка конкретной модели — её смотрят по схеме своей машины).
 * Тексты — ключи work_sch_* в i18n.
 */
object Schemes {
    enum class Role { Power, Ground, Signal, Ref, Control }

    /** Провод: номер на разъёме узла (условный), роль, куда идёт (ключ), что мерить и норма (ключ). */
    data class Wire(val pin: String, val role: Role, val toKey: String, val checkKey: String) {
        val to: String get() = tr(toKey)
        val check: String get() = tr(checkKey)
    }

    data class Node(val id: String, val families: Set<String>, val wires: List<Wire>, val tipKeys: List<String>) {
        val title: String get() = tr("work_sch_${id}")
        val about: String get() = tr("work_sch_${id}_about")
        val tips: List<String> get() = tipKeys.map { tr(it) }
    }

    val nodes = listOf(
        Node("lambda", setOf("lambda1", "lambda2", "lambda_heater", "lean", "rich", "catalyst", "afr"), listOf(
            Wire("1", Role.Power, "work_to_relay12", "work_ck_heater_power"),
            Wire("2", Role.Control, "work_to_ecu", "work_ck_heater_res"),
            Wire("3", Role.Signal, "work_to_ecu", "work_ck_o2_signal"),
            Wire("4", Role.Ground, "work_to_ecu_ground", "work_ck_signal_ground")
        ), listOf("work_tip_lambda_1", "work_tip_lambda_2")),
        Node("coil", setOf("coil", "misfire", "misfire_random", "ignition_module"), listOf(
            Wire("1", Role.Power, "work_to_ign12", "work_ck_coil_power"),
            Wire("2", Role.Ground, "work_to_body_ground", "work_ck_ground_res"),
            Wire("3", Role.Control, "work_to_ecu", "work_ck_coil_control")
        ), listOf("work_tip_coil_1", "work_tip_coil_2")),
        Node("ckp_ind", setOf("ckp", "ckp_cmp", "cmp"), listOf(
            Wire("1", Role.Signal, "work_to_ecu", "work_ck_ind_signal"),
            Wire("2", Role.Signal, "work_to_ecu", "work_ck_ind_res")
        ), listOf("work_tip_ckp_1", "work_tip_ckp_2")),
        Node("ckp_hall", setOf("ckp", "ckp_cmp", "cmp", "vss"), listOf(
            Wire("1", Role.Ref, "work_to_ecu_ref", "work_ck_hall_power"),
            Wire("2", Role.Ground, "work_to_ecu_ground", "work_ck_signal_ground"),
            Wire("3", Role.Signal, "work_to_ecu", "work_ck_hall_signal")
        ), listOf("work_tip_ckp_1")),
        Node("maf", setOf("maf", "lean", "rich"), listOf(
            Wire("1", Role.Power, "work_to_relay12", "work_ck_maf_power"),
            Wire("2", Role.Ground, "work_to_body_ground", "work_ck_ground_res"),
            Wire("3", Role.Ground, "work_to_ecu_ground", "work_ck_signal_ground"),
            Wire("4", Role.Signal, "work_to_ecu", "work_ck_maf_signal")
        ), listOf("work_tip_maf_1", "work_tip_maf_2")),
        Node("ntc", setOf("ect", "iat", "thermostat", "oil_temp", "fuel_temp", "trans_temp"), listOf(
            Wire("1", Role.Ref, "work_to_ecu_ref_res", "work_ck_ntc_volt"),
            Wire("2", Role.Ground, "work_to_ecu_ground", "work_ck_ntc_res")
        ), listOf("work_tip_ntc_1")),
        Node("etc", setOf("etc", "tps", "tps_corr", "iac", "idle"), listOf(
            Wire("1", Role.Control, "work_to_ecu", "work_ck_etc_motor"),
            Wire("2", Role.Control, "work_to_ecu", "work_ck_etc_motor"),
            Wire("3", Role.Ref, "work_to_ecu_ref", "work_ck_ref5"),
            Wire("4", Role.Ground, "work_to_ecu_ground", "work_ck_signal_ground"),
            Wire("5", Role.Signal, "work_to_ecu", "work_ck_tps1"),
            Wire("6", Role.Signal, "work_to_ecu", "work_ck_tps2")
        ), listOf("work_tip_etc_1", "work_tip_etc_2")),
        Node("injector", setOf("injector", "injector_balance", "fuel_low", "fuel_high"), listOf(
            Wire("1", Role.Power, "work_to_relay12", "work_ck_inj_power"),
            Wire("2", Role.Control, "work_to_ecu", "work_ck_inj_res")
        ), listOf("work_tip_inj_1")),
        Node("map", setOf("map", "boost_sensor", "baro"), listOf(
            Wire("1", Role.Ref, "work_to_ecu_ref", "work_ck_ref5"),
            Wire("2", Role.Ground, "work_to_ecu_ground", "work_ck_signal_ground"),
            Wire("3", Role.Signal, "work_to_ecu", "work_ck_map_signal")
        ), listOf("work_tip_map_1"))
    )

    fun byId(id: String) = nodes.firstOrNull { it.id == id }

    /** Схемы, подходящие к семейству кода. */
    fun forFamily(family: String?): List<Node> = if (family == null) emptyList() else nodes.filter { family in it.families }

    /**
     * Шаг проверки из базы можно измерить приложением прямо сейчас: по словам шага выбираем задачу живых датчиков.
     * null — шаг делается руками (мультиметр, осмотр, дым-тест).
     */
    fun taskForStep(step: String): String? {
        val s = step.lowercase()
        return when {
            Regex("2500|подсос|коррекц.*холост|холост.*коррекц").containsMatchIn(s) -> "leak" // i18n-ignore: слова из русской базы
            Regex("коррекц|лямбд|зонд|смес").containsMatchIn(s) -> "mix" // i18n-ignore
            Regex("расход воздуха|дмрв|г/с").containsMatchIn(s) -> "maf" // i18n-ignore
            Regex("катализ|второй зонд|второго зонда").containsMatchIn(s) -> "cat" // i18n-ignore
            Regex("напряжен.*(бортсет|генерат|заряд)|зарядк").containsMatchIn(s) -> "charge" // i18n-ignore
            Regex("температур.*(охлажд|двигател)|прогрев|термостат").containsMatchIn(s) -> "warm" // i18n-ignore
            Regex("холост|обороты|разрежен|давлени.*впуск").containsMatchIn(s) -> "idle" // i18n-ignore
            else -> null
        }
    }
}
