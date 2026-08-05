import java.util.Properties

plugins {
    id("com.android.application")
}

// Secrets live in local.properties (never committed):
//   claude.apiKey=sk-ant-...        -> enables the optional cloud scene-description feature
//   claude.model=claude-opus-5      -> optional override
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
        versionName = "0.1.0"

        buildConfigField(
            "String", "CLAUDE_API_KEY",
            "\"${localProps.getProperty("claude.apiKey", "")}\""
        )
        buildConfigField(
            "String", "CLAUDE_MODEL",
            "\"${localProps.getProperty("claude.model", "claude-opus-5")}\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources {
            // Apache HTTP jars (transitive deps of the Anthropic SDK) each
            // ship these metadata files; Android forbids duplicates.
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ONNX Runtime with the Qualcomm QNN Execution Provider.
    // Bundles libQnnHtp.so and friends, so YOLO runs on the Hexagon NPU (HTP)
    // of the S25 Ultra's Snapdragon 8 Elite with no NDK build of our own.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.28.0")

    // Official Anthropic SDK (Kotlin uses the Java SDK) for the optional
    // cloud scene-description feature.
    implementation("com.anthropic:anthropic-java:2.34.0")

    testImplementation("junit:junit:4.13.2")
}
