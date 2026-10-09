import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------- Release 签名 ----------
// 密钥库与口令不入库，只在构建时注入：
//   CI   ：GitHub Secrets → 环境变量 COLDFRONT_KEYSTORE_*（见 .github/workflows/release-stable.yml）
//   本机 ：-P 或 ~/.gradle/gradle.properties 中的 coldfront.* 属性
// 环境变量优先于 Gradle 属性。取值一律经 providers，配置缓存才能正确跟踪并在口令轮换后失效重算。
// 任一必填项缺失时回退为未签名构建，fork / PR（拿不到 Secrets）仍能正常编译。
fun signingValue(envName: String, propName: String): String? =
    providers.environmentVariable(envName).orNull ?: providers.gradleProperty(propName).orNull

val releaseKeystoreFile = signingValue("COLDFRONT_KEYSTORE_FILE", "coldfront.keystoreFile")
val releaseKeystorePassword = signingValue("COLDFRONT_KEYSTORE_PASSWORD", "coldfront.keystorePassword")
val releaseKeyAlias = signingValue("COLDFRONT_KEY_ALIAS", "coldfront.keyAlias")
// PKCS12 密钥库不支持与密钥库口令不同的条目口令，未单独提供时复用前者
val releaseKeyPassword =
    signingValue("COLDFRONT_KEY_PASSWORD", "coldfront.keyPassword") ?: releaseKeystorePassword

val releaseSigningReady = !releaseKeystoreFile.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    File(releaseKeystoreFile).isFile

if (!releaseSigningReady) {
    logger.lifecycle(
        "Release 签名未配置：assembleRelease 产出未签名 APK。" +
            "CI 检查 COLDFRONT_KEYSTORE_* Secrets，本机设置 coldfront.* Gradle 属性。"
    )
}

android {
    namespace = "io.github.wxmyyds.coldfront"
    compileSdk {
        // Compose 1.13-alpha（compose-bom-alpha）要求 compileSdk ≥ 37.1
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.wxmyyds.coldfront"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        // K90 Ultra / K90 Max are ARMv8 devices. Keep only the native ABI used by them.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 仅有完整凭据时才创建 release 签名配置，避免 AGP 因空配置报错
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = File(releaseKeystoreFile!!) // releaseSigningReady 已保证非空
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 未配置密钥时保持 AGP 默认（未签名），产出 app-release-unsigned.apk
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Compose UI; XML resources contain the manifest and launcher vector, plus product images.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.miuix.nav)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material.kolor)

    // Compose（版本由 alpha BOM 托管 → material3 1.5.0-alpha29）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.composables.icons.symbols.rounded)
    implementation(libs.composables.icons.symbols.rounded.filled)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real JSON implementation for repository tests; Android's mockable JAR only has stubs.
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
