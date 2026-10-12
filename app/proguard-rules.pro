# ---------- R8 / minify 规则 ----------
# release 构建开启 isMinifyEnabled + shrinkResources 后，R8 做可达性分析。
# 下列规则覆盖本工程里**确实存在反射/序列化风险**的位置；其余代码交给 R8 自动裁剪。

# Keep Kotlin metadata（kotlinx.serialization 与 Compose 都依赖它）
-keep class kotlin.Metadata { *; }

# ---------- kotlinx.serialization ----------
# AppRoute（ui/MainActivity.kt 的 @Serializable sealed interface）由 miuix-nav 负责
# 序列化进 rememberSaveable。生成的 $serializer / Companion 若被裁剪，路由在进程重建后
# 会解析失败。保留带 @Serializable 的类的序列化器与伴生对象。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.wxmyyds.coldfront.ui.**$$serializer { *; }
-keepclassmembers class io.github.wxmyyds.coldfront.ui.** {
    *** Companion;
}

# ---------- org.json 手工解析 ----------
# data/ProfileJson.kt 与 fan/FanCurve.kt 走 org.json 手工编解码，**不使用反射**，
# 理论上无需 keep。但两个文件都用 `CoolerDeviceType.entries` / `LightEffect.entries`
# 按 `.name` 反查，且落盘 JSON 里的枚举名字必须与类里的常量名逐字一致：
# 一旦 R8 重命名枚举常量，历史数据将全部解析失败并静默回退默认值。
# 因此固定这两个枚举的常量名（这是原 proguard 文件里已有的两条规则，保留）。
-keepclassmembers enum io.github.wxmyyds.coldfront.domain.CoolerDeviceType {
    <fields>;
}
-keepclassmembers enum io.github.wxmyyds.coldfront.domain.LightEffect {
    <fields>;
}

# ---------- Android 组件 ----------
# Activity / Service / Receiver 由 Manifest 引用，AGP 会自动生成 keep 规则。
# 磁贴服务 SystemFanTileService 响应系统绑定；前台服务 CoolerService 由系统启动。
# 保守起见显式保留这些入口类名（Manifest 里 .name 是字符串）。
-keep class io.github.wxmyyds.coldfront.ui.MainActivity { *; }
-keep class io.github.wxmyyds.coldfront.service.CoolerService { *; }
-keep class io.github.wxmyyds.coldfront.service.SystemFanTileService { *; }
-keep class io.github.wxmyyds.coldfront.service.BootReceiver { *; }

# ---------- Compose ----------
# Compose 运行时大量使用 lambda 与反射；官方建议保留 Composer 相关签名。
-dontwarn androidx.compose.**
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ---------- 通用警告抑制 ----------
# 第三方库（material-kolor / miuix-nav / icons）引用的可选依赖可能缺失。
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.**
-dontwarn okio.**
