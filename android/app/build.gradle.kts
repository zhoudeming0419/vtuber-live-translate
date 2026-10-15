plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * CI 渠道版本覆写：workflow 用 -PappVersionCode / -PappVersionName 传入自增版本，
 * 本地不传参时保持仓库内的固定值，与既有安装链路完全一致。
 */
val ciVersionCode: Int? = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
val ciVersionName: String? =
    (project.findProperty("appVersionName") as String?)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 更新清单所在的仓库。默认读当前正在用的 fork；换仓时用 -PupdateRepoOwner / -PupdateRepoName 覆写。
 * CI 把每次构建的 update.json 推进 main，手机端向 raw.githubusercontent.com 检查这里指向的仓库。
 */
fun releaseProp(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

android {
    namespace = "com.xyq.livetranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xyq.livetranslate"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode ?: 39
        versionName = ciVersionName ?: "2.6.1"

        buildFeatures {
            buildConfig = true
        }
        buildConfigField(
            "String",
            "UPDATE_REPO_OWNER",
            "\"${releaseProp("updateRepoOwner", "zhoudeming0419")}\"",
        )
        buildConfigField(
            "String",
            "UPDATE_REPO_NAME",
            "\"${releaseProp("updateRepoName", "vtuber-live-translate")}\"",
        )
    }

    signingConfigs {
        // 固定的 debug 签名：不配的话每台机器/每次 CI 会现生成随机 debug.keystore，
        // 导致每次构建签名不同、无法覆盖安装。指向仓库内固定 keystore 保证签名一致。
        // 口令为公开固定值，仅用于本地/内测包，非机密。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
