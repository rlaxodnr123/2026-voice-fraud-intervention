plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 실서비스 CallGuard(com.example.callguard) · 기존 실험앱(…​.experiment)과
    // 같은 기기에 나란히 설치되도록 별도 applicationId를 쓴다.
    namespace = "com.example.callguard.testapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.callguard.testapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Vosk 모델(.mdl/.fst 등)은 이미 압축된 바이너리다. aapt가 다시 압축하면
    // 기기에서 스트리밍으로 못 읽어 모델 로드가 실패한다.
    androidResources {
        noCompress += listOf("mdl", "fst", "dubm", "ie", "mat", "int", "conf", "stats", "txt")
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    applicationVariants.all {
        outputs.forEach { output ->
            val apkOutput = output as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            apkOutput?.outputFileName = "CallGuardTestApp.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Foreground Service (통화 중 백그라운드 전환에도 세션이 살아 있어야 한다)
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 온디바이스 한국어 STT (모델은 assets/model-ko — README 2-2 참고)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.32")

    testImplementation("junit:junit:4.13.2")
    // android.jar 스텁의 org.json은 유닛테스트에서 항상 기본값을 돌려준다.
    // 실제 구현을 테스트 클래스패스에 올려야 세션 로그 요약을 JVM에서 검증할 수 있다.
    testImplementation("org.json:json:20231013")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
