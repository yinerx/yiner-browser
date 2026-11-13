plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名只从环境变量读取（CI 中由 GitHub Actions secrets 注入），仓库里不存放任何密钥。
// 本地没有这些变量时，release 构建回退到 Android 默认调试签名，保证任何人都能构建。
val releaseKeystorePath: String? = System.getenv("YINER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
val hasReleaseSigning = releaseKeystorePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null
// CI 发布时传 -Pyiner.requireReleaseSigning=true：缺少签名配置直接失败，绝不发布调试签名的包
val requireReleaseSigning = providers.gradleProperty("yiner.requireReleaseSigning").orNull == "true"

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
        // versionCode 必须单调递增：应用内更新只比较它
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        // 应用内更新只信任这个仓库的 GitHub Releases
        buildConfigField("String", "UPDATE_REPO", "\"yinerx/yiner-browser\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                requireReleaseSigning -> throw GradleException(
                    "缺少发布签名：需要环境变量 YINER_KEYSTORE_FILE、SIGNING_STORE_PASSWORD、SIGNING_KEY_ALIAS、SIGNING_KEY_PASSWORD",
                )
                else -> signingConfigs.getByName("debug")
            }
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
    implementation("androidx.work:work-runtime:2.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
