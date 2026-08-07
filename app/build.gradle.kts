import java.util.Properties

plugins {
    id("com.android.application")
}

// local.properties (never committed):
//   maps.apiKey=AIza...   -> enables walking navigation (Geocoding + Routes API)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.quad.shepherd"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.quad.shepherd"
        minSdk = 31 // Snapdragon 8 Gen 1+ era; primary target is the Galaxy S25 Ultra (API 35)
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"

        buildConfigField(
            "String", "MAPS_API_KEY",
            "\"${localProps.getProperty("maps.apiKey", "")}\""
        )
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("maps.apiKey", "")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // GenieX dlopens its plugin .so files by ABSOLUTE PATH from
            // nativeLibraryDir; with useLegacyPackaging=false the libs stay
            // inside the APK and that path is empty -> "Invalid plugin".
            // Legacy packaging extracts them to disk (which is also what
            // lets the Hexagon DSP open libQnnHtpV79Skel.so by file path).
            useLegacyPackaging = true
            // GenieX, its qnn-runtime dep, and onnxruntime-android-qnn all
            // ship libQnn*.so. Keep one copy — declaration order makes
            // GenieX's QNN 2.42 win, which ORT then loads as well.
            pickFirsts += setOf(
                "lib/arm64-v8a/libQnn*.so",
                "lib/arm64-v8a/libc++_shared.so",
            )
        }
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // GenieX: Qualcomm's on-device GenAI runtime (LLM/VLM on NPU/GPU/CPU).
    // Ships QNN 2.42 native libs; models pulled on-device (HF/AI Hub).
    // Declared BEFORE onnxruntime so its newer QNN libs win the jniLibs merge.
    implementation("com.qualcomm.qti:geniex-android:0.3.16")

    // sherpa-onnx (static-link-onnxruntime build: its ORT is linked INTO
    // libsherpa-onnx-jni.so, so it cannot clash with the QNN ORT below).
    // Powers the neural Kokoro TTS voice; KokoroFetcher self-provisions
    // the model files on first run.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))

    // ONNX Runtime with the Qualcomm QNN Execution Provider (vision models).
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.28.0")

    // On-demand sign/menu reading — bundled Latin model, fully offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ARCore: 6-DoF motion tracking + the Depth API. The areamap needs a
    // pose to stamp obstacles against, and ARCore's depth is metric by
    // construction — between them they replace the monocular depth model
    // and the ground-plane self-calibration. See Loadout.
    implementation("com.google.ar:core:1.54.0")

    // GPS fixes for walking navigation (route following runs locally;
    // only route/geocode requests leave the phone).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Route mini-map during navigation (lite mode).
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // tar.bz2 unpacking for the Kokoro voice package.
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}
