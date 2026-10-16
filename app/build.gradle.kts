plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sinsluhi.obdai"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sinsluhi.obdai"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.2.2"
        // Провайдер и ключ ИИ из секретов GitHub (AI_PROVIDER, AI_API_KEY, AI_MODEL); пусто, если не заданы
        fun env(name: String) = System.getenv(name)?.trim().orEmpty()
        buildConfigField("String", "AI_PROVIDER", "\"" + env("AI_PROVIDER").ifBlank { "groq" } + "\"")
        buildConfigField("String", "AI_API_KEY", "\"" + env("AI_API_KEY").ifBlank { env("ANTHROPIC_API_KEY") } + "\"")
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
