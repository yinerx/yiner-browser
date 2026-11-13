plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GeckoView 稳定版（Mozilla 官方 Maven，MPL-2.0）。只引入 arm64-v8a 变体。
val geckoviewVersion = "160.0.20261104181552"

android {
    namespace = "io.yiner.browser"
    // GeckoView 160 的 AAR 元数据要求 compileSdk ≥ 37.2
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.yiner.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // GeckoView 版本号是日期戳，不需要提示“有新版本”
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // 刻意只打 arm64-v8a（手机自用），不支持 ChromeOS x86_64
        disable += "ChromeOsAbiSupport"
        // targetSdk 36 是已在 Android 16 行为下设计的版本；37 的行为变更未经验证，暂不提升
        disable += "OldTargetApi"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoviewVersion")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
