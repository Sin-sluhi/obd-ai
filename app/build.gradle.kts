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
        versionCode = 4
        versionName = "0.4"
        // Провайдер и ключ ИИ из секретов GitHub (AI_PROVIDER, AI_API_KEY, AI_MODEL); пусто, если не заданы
        fun env(name: String) = System.getenv(name)?.trim().orEmpty()
        buildConfigField("String", "AI_PROVIDER", "\"" + env("AI_PROVIDER").ifBlank { "groq" } + "\"")
        buildConfigField("String", "AI_API_KEY", "\"" + env("AI_API_KEY").ifBlank { env("ANTHROPIC_API_KEY") } + "\"")
        buildConfigField("String", "AI_MODEL", "\"" + env("AI_MODEL") + "\"")
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
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
