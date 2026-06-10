import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// local.properties에서 HF_TOKEN 읽기
val localProps = rootProject.file("local.properties")
val hfToken: String = if (localProps.exists()) {
    Properties().apply { localProps.inputStream().use { load(it) } }
        .getProperty("HF_TOKEN", "")
} else ""

android {
    namespace = "com.vrtmv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrtmv.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "HF_TOKEN", "\"$hfToken\"")
    }

    buildTypes {
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
        // litertlm 0.11.0이 Kotlin 2.3.x로 컴파일되어 있어 메타데이터 버전 검증을 스킵.
        // KSP가 Kotlin 2.3.x를 아직 지원하지 않아 정식 bump 불가 (KSP 최신 2.2.21-2.0.5).
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // CameraX (ARCore 미지원/실패 기기 폴백 경로용)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ARCore (월드 앵커 기반 AR 오버레이)
    implementation(libs.arcore)

    // MediaPipe
    implementation(libs.mediapipe.vision)

    // LiteRT-LM (on-device, Gemma 4 MTP 지원)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // TFLite (YOLOv11n 검출기용) — YOLOv11n GPU delegate 이슈로 GPU 변종은 제외, CPU 4스레드 고정
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
