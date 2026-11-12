import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ------------------------------------------------------------------
// 签名配置（工程内不保存任何密钥/口令文件）
// 来源优先级：GM_KEYSTORE_PROPERTIES（环境变量或 -D 系统属性）
//            -> 默认 D:/Development/gm-keys/keystore.properties
// 字段：storeFile / storePassword / keyAlias / keyPassword（storeFile 支持绝对路径）
// 无 properties 时可用环境变量：GM_STORE_FILE / GM_STORE_PASSWORD / GM_KEY_ALIAS / GM_KEY_PASSWORD
// ------------------------------------------------------------------
val keystorePropsPath: String =
    System.getenv("GM_KEYSTORE_PROPERTIES")
        ?: System.getProperty("GM_KEYSTORE_PROPERTIES")
        ?: "D:/Development/gm-keys/keystore.properties"

val keystorePropsFile = File(keystorePropsPath)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String, fallback: String? = null): String? =
    keystoreProps.getProperty(propKey)
        ?: System.getenv(envKey)
        ?: System.getProperty(envKey)
        ?: fallback

val releaseStorePath = signingValue("storeFile", "GM_STORE_FILE")
val releaseStoreFile = releaseStorePath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
val releaseStorePassword = signingValue("storePassword", "GM_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "GM_KEY_ALIAS", "gamemonitor")
val releaseKeyPassword = signingValue("keyPassword", "GM_KEY_PASSWORD") ?: releaseStorePassword

android {
    namespace = "com.gamemonitor.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamemonitor.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "0.1.3"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStoreFile.isFile) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 关闭 R8/资源压缩，避免反射/清单内的 Service、无障碍类被剥离
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// 发布包必须带签名：缺配置时直接报错，避免静默产出未签名 APK
val verifyReleaseSigning by tasks.registering {
    doLast {
        val missing = mutableListOf<String>()
        if (releaseStoreFile == null) {
            missing += "storeFile（GM_STORE_FILE 或 properties 中的 storeFile）"
        } else if (!releaseStoreFile.isFile) {
            missing += "密钥库文件不存在：${releaseStoreFile.absolutePath}"
        }
        if (releaseStorePassword.isNullOrBlank()) missing += "storePassword（GM_STORE_PASSWORD）"
        if (releaseKeyAlias.isNullOrBlank()) missing += "keyAlias（GM_KEY_ALIAS）"
        if (releaseKeyPassword.isNullOrBlank()) missing += "keyPassword（GM_KEY_PASSWORD）"
        if (missing.isNotEmpty()) {
            throw GradleException(
                "未找到可用的 release 签名配置：${keystorePropsFile.absolutePath}\n" +
                    "缺少：" + missing.joinToString("、") + "\n" +
                    "请先按 README「发布与分发」在工程外生成密钥（D:\\Development\\gm-keys），" +
                    "或通过 GM_KEYSTORE_PROPERTIES 指定 properties 文件。"
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

