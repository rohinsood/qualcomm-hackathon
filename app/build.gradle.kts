plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wayfinder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wayfinder.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"

        // QAIRT/QNN targets arm64. Restrict ABIs to avoid pulling x86 NDK bits.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TFLite host for the segmentation model. The QNN/HTP delegate AAR is added
    // separately (see app/README and perception/seg/TFLiteSegmentationRunner.kt).
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api) // GpuDelegateFactory — needed to construct the GPU delegate

    // Qualcomm QNN TFLite delegate (from the QNN SDK) → runs models on the Hexagon NPU (HTP).
    implementation(files("libs/qtld-release.aar"))

    // ONNX Runtime with the QNN execution provider (QAIRT) — runs .onnx on the
    // Hexagon NPU via addQnn(backend=htp). Superset of the plain onnxruntime-android.
    implementation(libs.onnxruntime.android.qnn)

    implementation(libs.kotlinx.coroutines.android)
}
