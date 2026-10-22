plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    lint {
        // минимальная версия — Android 7.0: вызов API новее без проверки версии = красная сборка
        checkOnly += setOf("NewApi", "InlinedApi")
        abortOnError = true
        checkReleaseBuilds = false
    }
    namespace = "io.github.sinsluhi.obdai"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sinsluhi.obdai"
        minSdk = 24
        targetSdk = 35
        fun env(name: String) = System.getenv(name)?.trim().orEmpty()
        // Номер версии растёт с каждой сборкой CI (Google Play не принимает повторный номер); локально — 14
        versionCode = env("GITHUB_RUN_NUMBER").toIntOrNull()?.let { 100 + it } ?: 14
        versionName = "1.2." + env("GITHUB_RUN_NUMBER").ifEmpty { "2" }
        // Сборка для магазина (release, AAB): по правилам Google Play о сборе данных показывается окно про журнал
        buildConfigField("boolean", "STORE", (env("STORE_BUILD") == "true").toString())
        // Провайдер и ключ ИИ из секретов GitHub (AI_PROVIDER, AI_API_KEY, AI_MODEL); пусто, если не заданы
        buildConfigField("String", "AI_PROVIDER", "\"" + env("AI_PROVIDER").ifBlank { "groq" } + "\"")
        // ключи не открытым текстом: ключ XOR маска (та же маска в Secrets.kt), чтобы «gsk_…» не находился поиском по APK
        val mask = intArrayOf(0x5A, 0x13, 0xC7, 0x2E, 0x91, 0x6B, 0xF4, 0x08, 0x3D, 0xA6, 0x77, 0xE2, 0x19, 0xB5, 0x4C, 0x80)
        fun xorHex(v: String) = v.mapIndexed { i, c -> "%02x".format(c.code xor mask[i % mask.size]) }.joinToString("")
        buildConfigField("String", "AI_API_KEY_X", "\"" + xorHex(env("AI_API_KEY").ifBlank { env("ANTHROPIC_API_KEY") }) + "\"")
        buildConfigField("String", "SIGN_SHA256", "\"1d9ad0322dc001c53afa1d4b04e9eecd092a884f969510709a46ec86450b9147\"")
        buildConfigField("String", "OPENROUTER_KEY_X", "\"" + xorHex(env("OPENROUTER_KEY")) + "\"")
        buildConfigField("String", "AI_MODEL", "\"" + env("AI_MODEL") + "\"")
        // Адрес сервера форума (server/forum), переменная репозитория FORUM_URL; пусто = чат выключен
        buildConfigField("String", "FORUM_URL", "\"" + env("FORUM_URL") + "\"")
        // Режим разработчика (7 нажатий на версию): в сборке для пользователей (CI) выключен, локально — есть.
        // Переменная репозитория DEV_TOOLS=true включает его и в CI (только для своей тестовой сборки).
        val devTools = env("DEV_TOOLS").ifBlank { if (env("CI") == "true") "false" else "true" } == "true"
        // не константа (parseBoolean), иначе Kotlin вшивает значение в код экранов и при быстрой пересборке
        // версии разработчика в CI экраны могли остаться с прежним значением
        buildConfigField("boolean", "DEV_TOOLS", "Boolean.parseBoolean(\"$devTools\")")
        // Ключ владельца для разбора через Claude на его компьютере (секрет OWNER_AI_KEY): только в версии разработчика,
        // в пользовательскую сборку не попадает никогда — подписка Claude личная
        buildConfigField("String", "OWNER_AI_KEY", "\"" + (if (devTools) env("OWNER_AI_KEY") else "") + "\"")
        // постоянный адрес компьютера владельца (Tailscale): с телефона в той же сети Tailscale доступен всегда,
        // даже когда публичный вход и туннели сбоят; пусто — берётся текущий адрес сервера форума
        buildConfigField("String", "OWNER_AI_URL", "\"" + (if (devTools) env("OWNER_AI_URL") else "") + "\"")
    }
    // Постоянный ключ подписи (секреты SIGNING_*; CI раскладывает .jks и задаёт SIGNING_KEYSTORE_FILE).
    // Без него каждая сборка подписывалась случайным debug-ключом раннера и не вставала поверх прошлой версии.
    // Локально без переменных сборка подписывается обычным debug-ключом, как раньше.
    val keystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("stable") {
            storeFile = keystore
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        // Клиентская сборка (сайт, Авито) и Google Play — релиз тем же постоянным ключом: R8 запутывает и ужимает код,
        // отладчик не подключить. Без ключа (форки, android7.yml) — отладочный ключ, чтобы APK ставился на эмулятор.
        getByName("release") {
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // repeatOnLifecycle/LocalLifecycleOwner для единственного withFrameNanos-цикла фона (ui/Motion.kt); та же линейка 2.8, что тянет compose-ui 1.7
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // разбор ответов адаптера проверяется тестами на записанных ответах: python-подобные регрессии не ловятся сборкой
    testImplementation("junit:junit:4.13.2")
    // настоящий org.json для JVM-тестов: в android.jar он заглушка и бросает «not mocked»
    testImplementation("org.json:json:20240303")
}
