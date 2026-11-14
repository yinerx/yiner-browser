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


android {
    namespace = "io.yiner.browser"
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
        // 内核为系统 WebView，APK 不含原生库，适用于所有 CPU 架构
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
        debug {
            // 测试包与正式版并存安装：不同包名、名称带“测试版”，不会覆盖或要求卸载正式版
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "银耳浏览器 测试版")
        }
        release {
            resValue("string", "app_name", "银耳浏览器")
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
        // 用于按构建类型提供应用名称（正式版 / 测试版）
        resValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // targetSdk 36 是已在 Android 16 行为下设计的版本；37 的行为变更未经验证，暂不提升
        disable += "OldTargetApi"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // 界面截图测试（Robolectric 离线渲染）需要合并后的资源
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 只有显式 -Pyiner.screenshots=true 时才渲染界面截图；CI 常规单元测试不运行
            it.systemProperty("yiner.screenshots", providers.gradleProperty("yiner.screenshots").orNull ?: "false")
            it.systemProperty("yiner.screenshots.dir", rootProject.layout.buildDirectory.dir("ui-screenshots").get().asFile.absolutePath)
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    // 内核：Android 系统 WebView（基于 Chromium，由系统/Play 商店更新），androidx.webkit 提供多配置文件等兼容接口
    implementation("androidx.webkit:webkit:1.18.0")

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
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
