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
        versionCode = 2
        versionName = "0.2"
        // Ключ ИИ из секрета GitHub ANTHROPIC_API_KEY; пустая строка, если секрета нет
        buildConfigField("String", "DEFAULT_API_KEY", "\"" + (System.getenv("ANTHROPIC_API_KEY") ?: "") + "\"")
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
