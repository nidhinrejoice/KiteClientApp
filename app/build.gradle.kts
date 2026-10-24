import java.util.Properties

val kiteProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun configString(key: String) = "\"" + kiteProperties.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.kiteclientapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.kiteclientapp"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "KITE_API_KEY", configString("kite.apiKey"))
        buildConfigField("String", "KITE_API_SECRET", configString("kite.apiSecret"))
        buildConfigField("String", "KITE_REDIRECT_URL", configString("kite.redirectUrl"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
