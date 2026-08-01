import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionCode = 3
val appVersionName = "1.1.1"

val releaseProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun releaseValue(environment: String, property: String) =
    System.getenv(environment)?.takeIf(String::isNotBlank) ?: releaseProperties.getProperty(property)?.takeIf(String::isNotBlank)

val releaseKeystore = releaseValue("KEYSTORE_PATH", "storeFile")
val releaseStorePassword = releaseValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseValue("KEY_PASSWORD", "keyPassword")
require(releaseKeystore == null || listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
    "Assinatura incompleta no ambiente ou em keystore.properties."
}

android {
    namespace = "com.kitsuneandroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kitsuneandroid"
        minSdk = 24
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: appVersionCode
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.jlibtorrent)
    runtimeOnly(libs.jlibtorrent.android.arm)
    runtimeOnly(libs.jlibtorrent.android.arm64)
    runtimeOnly(libs.jlibtorrent.android.x86)
    runtimeOnly(libs.jlibtorrent.android.x8664)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
