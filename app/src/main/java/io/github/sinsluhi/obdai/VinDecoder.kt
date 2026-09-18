package io.github.sinsluhi.obdai

/** Расшифровка VIN без интернета: марка по WMI, год по 10-му символу, модель для АвтоВАЗа по VDS. */
object VinDecoder {

    data class Info(val brand: String?, val model: String?, val year: Int?) {
        val isEmpty get() = brand == null && model == null && year == null

        /** Короткое название: «Lada Granta 2013» или «Hyundai 2019». */
        fun title(): String = listOfNotNull(brand, model, year?.toString()).joinToString(" ")

        /** Дополнить тем, как машину назвала нейронка («Hyundai Tucson 2019»): модель и год, если VIN их не дал. */
        fun withCar(text: String?): Info {
            val t = text?.trim().orEmpty()
            if (t.isBlank()) return this
            val y = year ?: Regex("\\b(19|20)\\d{2}\\b").find(t)?.value?.toIntOrNull()
            return Info(brand ?: t.substringBefore(' '), model ?: t, y)
        }

        /** Строка для отчёта нейронке. */
        fun describe(): String = buildString {
            brand?.let { append("марка $it") }
            model?.let { if (isNotEmpty()) append(", "); append("модель $it") }
            year?.let { if (isNotEmpty()) append(", "); append("год выпуска $it") }
        }
    }

    private val wmi = listOf(
        listOf("XTA", "XTC") to "Lada (АвтоВАЗ)", listOf("XTT") to "УАЗ", listOf("X96", "XTH") to "ГАЗ",
        listOf("WBA", "WBS", "WBY", "X4X") to "BMW", listOf("WMW") to "MINI",
        listOf("WDB", "WDC", "WDD", "WDF", "W1K", "W1N", "W1V", "X5U") to "Mercedes-Benz",
        listOf("WAU", "WA1", "WUA") to "Audi", listOf("WVW", "WVG", "WV1", "WV2", "XW8") to "Volkswagen",
        listOf("TMB", "TMP") to "Skoda", listOf("VSS") to "SEAT", listOf("WP0", "WP1") to "Porsche",
        listOf("KNA", "KNB", "KNC", "KND", "KNE", "KNM", "KNH", "U5Y", "U6Y") to "Kia",
        listOf("KMH", "KM8", "KMF", "Z94", "TMA") to "Hyundai", listOf("XWE") to "Hyundai / Kia (завод в Петербурге)",
        listOf("JTH", "JTJ") to "Lexus", listOf("JT", "SB1", "XW7", "2T1", "4T1", "5TD", "MR0", "MHF") to "Toyota",
        listOf("JHM", "JHL", "SHH", "SHS", "1HG", "2HG", "19X", "19U") to "Honda",
        listOf("JN1", "JN8", "JN6", "SJN", "Z8N", "VSK", "1N4", "1N6", "5N1") to "Nissan",
        listOf("JM1", "JMZ", "JM3", "JM7", "3MZ") to "Mazda", listOf("JF1", "JF2", "4S3", "4S4") to "Subaru",
        listOf("JA3", "JA4", "JMB", "MMC", "MMB", "JMY") to "Mitsubishi",
        listOf("1FA", "1FM", "1FT", "WF0", "X9F", "Z6F", "3FA", "MAJ") to "Ford",
        listOf("VF1", "VF2", "X7L", "VNV") to "Renault", listOf("UU1") to "Dacia / Renault",
        listOf("LVV", "LVT") to "Chery / Exeed / Omoda", listOf("LGW") to "Haval / Great Wall / Tank", listOf("L6T") to "Geely",
        listOf("LS5", "LS4") to "Changan", listOf("LFP") to "FAW", listOf("LFV") to "Volkswagen (FAW-VW)", listOf("LSG") to "Chevrolet (SAIC-GM)",
        listOf("LGJ", "LDC") to "Dongfeng", listOf("LMG") to "GAC", listOf("LGX") to "BYD", listOf("LLV") to "Lifan", listOf("LJ1") to "JAC / Москвич",
        listOf("YV1", "YV4", "LYV") to "Volvo", listOf("W0L", "W0V", "XUF", "XWF") to "Opel",
        listOf("VF3", "VR3") to "Peugeot", listOf("VF7", "VR7") to "Citroën", listOf("VR1", "VXK") to "DS / Opel",
        listOf("1G1", "KL1", "XUU", "X9L", "2G1", "3G1", "KL4", "KL8") to "Chevrolet",
        listOf("ZFA", "ZFF") to "Fiat", listOf("ZAR") to "Alfa Romeo", listOf("SAL", "SAJ") to "Land Rover / Jaguar",
        listOf("TRU", "WVZ") to "Audi", listOf("KPT", "X7M") to "SsangYong", listOf("MAL") to "Hyundai (Индия)",
        listOf("JS", "TSM", "MA3") to "Suzuki", listOf("JNK", "JNR", "JNZ") to "Infiniti", listOf("JH4") to "Acura",
        listOf("1J4", "1J8", "1C4", "ZAC") to "Jeep", listOf("1C3", "2C3") to "Chrysler", listOf("1B3", "2B3", "1D7", "3D4") to "Dodge",
        listOf("1G6", "1GY") to "Cadillac", listOf("KMT") to "Genesis", listOf("XWB", "KLA", "KLY") to "Daewoo / Ravon",
        listOf("WME") to "Smart", listOf("SCC") to "Lotus", listOf("ZLA") to "Lancia", listOf("VSK") to "Nissan",
        listOf("5YJ", "7SA", "LRW") to "Tesla", listOf("L6T") to "Geely / Zeekr"
    )

    /** Модели АвтоВАЗа по символам 4–7. */
    private val ladaModels = mapOf(
        "2190" to "Granta", "2191" to "Granta лифтбек", "2192" to "Kalina 2 хэтчбек", "2194" to "Kalina 2 универсал",
        "1117" to "Kalina универсал", "1118" to "Kalina седан", "1119" to "Kalina хэтчбек",
        "2170" to "Priora седан", "2171" to "Priora универсал", "2172" to "Priora хэтчбек",
        "2180" to "Vesta", "2181" to "Vesta SW", "2182" to "Vesta Cross",
        "2121" to "Niva 4x4 (2121)", "2131" to "Niva 4x4 (2131)", "2123" to "Niva Travel",
        "2110" to "2110", "2111" to "2111", "2112" to "2112", "2113" to "2113", "2114" to "2114", "2115" to "2115",
        "2104" to "2104", "2105" to "2105", "2106" to "2106", "2107" to "2107",
        "2129" to "XRAY", "GAB1" to "XRAY", "GFL1" to "Vesta", "GFK1" to "Vesta SW", "RS0" to "Largus", "KS0" to "Largus", "FS0" to "Largus фургон"
    )

    /** Hyundai (KMH…): 4-й символ VIN — линейка. */
    private val hyundaiLines = mapOf(
        'C' to "Accent", 'D' to "Elantra", 'E' to "Sonata", 'H' to "i30", 'J' to "Tucson",
        'S' to "Santa Fe", 'L' to "Grandeur", 'T' to "Genesis", 'G' to "i40", 'N' to "Kona"
    )

    /** Kia (KNA…): 4-й символ VIN — линейка. */
    private val kiaLines = mapOf(
        'D' to "Rio", 'P' to "Sportage", 'F' to "Cerato", 'K' to "Optima", 'M' to "Sorento",
        'B' to "Picanto", 'J' to "Soul", 'H' to "Ceed", 'C' to "Carnival", 'R' to "Mohave"
    )

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun decode(vin: String?): Info {
        val v = vin?.trim()?.uppercase().orEmpty()
        if (v.length < 3) return Info(null, null, null)
        val brand = wmi.firstOrNull { (prefixes, _) -> prefixes.any { v.startsWith(it) } }?.second
        var model: String? = null
        if (brand != null && v.length >= 7) {
            val vds = v.substring(3, 7)
            model = when {
                brand.startsWith("Lada") -> ladaModels[vds] ?: ladaModels[vds.take(3)]
                v.startsWith("KMH") -> hyundaiLines[v[3]]
                v.startsWith("KNA") -> kiaLines[v[3]]
                else -> null
            }
        }
        val year = if (v.length >= 10) yearFrom(v[9]) else null
        return Info(brand, model, year)
    }

    private fun yearFrom(c: Char): Int? {
        val idx = YEAR_CODES.indexOf(c)
        if (idx < 0) return null
        // A=2010 … Y=2030, 1=2031… совпадает с 2001–2009 для цифр: берём ближайший к сегодняшнему дню вариант
        // A…Y = 2010…2030, цифры 1…9 = 2001…2009
        return if (idx < 21) 2010 + idx else 2001 + (idx - 21)
    }
}
