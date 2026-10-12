import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------- 版本号：yy.mm.dd-n ----------
// 年月日 + **当天第几版**。日期在**配置阶段读取当前系统日期**，所以同一天多次构建
// 自动递增 n，跨天自动归 1，不需要手工改任何东西。
//
// 计数依据是"今天已经发布出去的 APK 编号"而不是 Gradle 缓存：落盘在
// `app/build/version-counter.properties`（构建产物目录，clean 后归零是预期行为）。
// 用昨天的日期做 key 就会重新从 1 开始，无需显式清理。
//
// 为什么不用 Gradle 的 buildNumber 或时间戳：
//   - 时间戳（yyyyMMddHHmm）不可读，且同一分钟内并发构建会撞号；
//   - CI 的 run number 全局递增，跨天不会归 1，和"当天第几版"的语义不符。
val versionDate: LocalDate = LocalDate.now(ZoneId.systemDefault())
val versionDateKey: String = versionDate.format(DateTimeFormatter.ofPattern("yy.MM.dd"))

val versionCounterFile = layout.buildDirectory.file("version-counter.properties").get().asFile

/** 当天版本序号。同一天第一次构建为 1，之后每次 +1；换天自动重置。 */
val buildSerialForToday: Int = run {
    val props = Properties()
    if (versionCounterFile.isFile) {
        versionCounterFile.inputStream().use { props.load(it) }
    }
    val next = (props.getProperty(versionDateKey)?.toIntOrNull() ?: 0) + 1
    props.setProperty(versionDateKey, next.toString())
    versionCounterFile.parentFile.mkdirs()
    versionCounterFile.outputStream().use { props.store(it, "ColdFront build serial per day") }
    next
}

val coldfrontVersionName = "$versionDateKey-$buildSerialForToday"

/**
 * versionCode 必须**单调递增**，否则系统拒绝安装（INSTALL_FAILED_VERSION_DOWNGRADE）。
 * 版本名 yy.MM.dd-n 直接映射成整数：yymmdd * 100 + n
 * （例：26.10.11-3 → 26101103）。n 上限 99，超过也只是数值继续涨，不会回绕。
 */
val coldfrontVersionCode = versionDateKey.replace(".", "").toInt() * 100 + buildSerialForToday

logger.lifecycle("ColdFront 版本：$coldfrontVersionName (versionCode $coldfrontVersionCode)")

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
        versionCode = coldfrontVersionCode
        versionName = coldfrontVersionName
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
            // 开启 R8 代码裁剪与资源裁剪。此前为 false，导致整个 Compose 全家桶、
            // 两套 Material Symbols 图标库、以及蓝牙散热器时代的残留代码全部进包
            // （15 个 dex，classes.dex 单个 19.2 MB，APK 22.1 MB）。
            isMinifyEnabled = true
            // 资源裁剪依赖代码裁剪：两者必须同时开启，否则未引用资源仍会保留。
            isShrinkResources = true
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
