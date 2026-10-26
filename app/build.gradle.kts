import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 簽章：本機讀 keystore/keystore.properties，CI 讀環境變數（GitHub Secrets）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")?.let { rootProject.file(it) }
// CI 以 build 編號當 versionCode，新版才能覆蓋安裝舊版；本機打包的版本名稱是 1.0.dev
val ciBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull()
val buildNumber = ciBuild ?: 1

android {
    namespace = "com.comichub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.comichub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = if (ciBuild != null) "1.0.$ciBuild" else "1.0.dev"
    }

    signingConfigs {
        if (releaseStoreFile?.exists() == true) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
