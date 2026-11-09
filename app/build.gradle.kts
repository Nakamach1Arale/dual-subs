import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx is published as a release AAR on GitHub, not on Maven: fetch it once.
val sherpaVersion = "2.0.1"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    val part = File(sherpaAar.path + ".part")
    URI(url).toURL().openStream().use { input -> part.outputStream().use { out -> input.copyTo(out) } }
    check(part.renameTo(sherpaAar)) { "could not save $sherpaAar" }
}

android {
    namespace = "io.github.dualsubs"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dualsubs"
        minSdk = 29          // AudioPlaybackCapture needs Android 10+
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        // Phones only: keeps the native sherpa-onnx / onnxruntime libraries out of the APK for x86.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    // The VAD model is read straight from assets; keep it uncompressed.
    androidResources {
        noCompress += setOf("onnx", "txt")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/CONTRIBUTORS*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:3.2.0")
    // Offline speech recognition: https://github.com/k2-fsa/sherpa-onnx (v2.0.1 release AAR)
    implementation(files(sherpaAar))
    // Japanese morphological analyzer + IPADIC, for kana readings above kanji (furigana).
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}
