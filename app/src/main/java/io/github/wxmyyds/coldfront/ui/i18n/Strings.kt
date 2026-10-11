package io.github.wxmyyds.coldfront.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * 100% Kotlin 的双语字符串表（中文 + 英文）。按系统 locale 切换。
 * 不使用 strings.xml，保持工程尽量纯 Kotlin。
 *
 * 本 fork 只控制 **REDMI K90 至尊版内置风扇**，因此表里不再有蓝牙散热器的
 * 扫描/设备/灯效文案 —— 那些页面已经移除，留着只会误导用户。
 */
interface AppStrings {
    /** 当前文案是否中文(枚举名本地化用) */
    val langIsZh: Boolean
    val appName: String

    // —— 通用 ——
    val cancel: String
    val delete: String
    val back: String
    val retry: String
    val close: String

    // —— 导航 ——
    val navHome: String
    val navSettings: String

    // —— 首页：控制 ——
    val homeTitle: String
    val homeFanSwitch: String
    val homeFanSwitchDesc: String
    val homeConnected: String
    val homeConnecting: String
    val homeSmart: String
    val homeSmartDesc: String
    val homeFullSpeed: String
    val homeFullSpeedDesc: String
    val homeLevel: String
    val homeLevelGear: String
    val homeLevelPercent: String
    val homeSmartActiveLevel: String
    val homeTemp: String
    val telemetryDegradedTitle: String
    val telemetryDegradedHint: String
    val homeControlFailedTitle: String
    val homeControlFailedHint: String

    // —— 首页：未启用 / 未就绪 ——
    val homeNotActivated: String
    val homeNotActivatedHint: String
    val homeFailed: String
    val homeFailedHint: String
    val homeActivate: String
    val homeRecheck: String

    // —— 运行检查 ——
    val checkTitle: String
    val checkSubtitle: String
    val checkModel: String
    val checkRoot: String
    val checkNode: String
    val checkTemperature: String
    val checkSpeed: String
    val checkNotification: String
    val checkPass: String
    val checkFail: String
    val checkSkip: String
    val checkRunning: String
    val checkRetry: String
    val checkNotificationGrant: String
    val checkNotificationHint: String
    val checkSummaryReady: String
    val checkSummaryNotReady: String
    val checkRequiredHint: String

    // —— 首次启动引导 ——
    val welcomeTitle: String
    val welcomeSubtitle: String
    val onboardingNext: String
    val onboardingPrev: String
    val onboardingStart: String
    val rootChecking: String
    val rootGranted: String
    val rootDenied: String
    val rootHint: String
    val rootRetry: String
    val doneTitle: String
    val doneReady: String
    val doneNotReady: String
    val doneEnter: String
    val doneEnterAnyway: String
    val doneClose: String
    val checkOptionalOff: String

    // —— 设置页 ——
    val settingsTitle: String
    val settingsDynamicColor: String
    val settingsDynamicColorDesc: String
    val settingsThemeMode: String
    val settingsPalette: String
    val settingsPaletteTonalSpot: String
    val settingsPaletteNeutral: String
    val settingsPaletteVibrant: String
    val settingsPaletteExpressive: String
    val settingsPaletteRainbow: String
    val settingsPaletteFruitSalad: String
    val settingsPaletteMonochrome: String
    val settingsPaletteFidelity: String
    val settingsPaletteContent: String
    val settingsPredictiveBack: String
    val settingsPredictiveBackDesc: String
    val settingsRecheck: String
    val settingsRecheckDesc: String
    val settingsFollowSystem: String
    val settingsDarkModeLight: String
    val settingsDarkModeDark: String
    val settingsLanguage: String
    val settingsAbout: String
    val settingsAboutReport: String
    val settingsAboutProject: String
    val aboutOpenLinkFailed: String
    val storageOperationFailed: String

    // —— 自动模式/服务 ——
    val serviceChannelName: String
    val serviceChannelDesc: String
    val serviceAutoOn: String
    val serviceSwitchManual: String
    val serviceReconnect: String
    val serviceWaitingConfig: String
    val serviceManual: String
    val serviceUnavailable: String
    val serviceControlFailed: String

    // —— 温控曲线 ——
    val curveTitle: String
    val curveDescription: String
    val curveBaseline: String
    val curveNow: String
    val curvePresetQuiet: String
    val curvePresetBalanced: String
    val curvePresetPerformance: String
    val curvePresetCustom: String
    val curvePointFormat: String
    val curveTempAxis: String
    val curveDutyAxis: String
    val curveSave: String
    val curveRevert: String
    val curveSaved: String
    val curveLivePointFormat: String
    val curveDangerTitle: String
    val curveDangerHintFormat: String
    val curveDangerConfirmTitle: String
    val curveDangerConfirmBody: String
    val curveDangerConfirmSave: String
    val curveDangerConfirmBack: String
    val curveCorruptTitle: String
    val curveCorruptHint: String
    val curveCorruptRepair: String

    // —— 息屏停转与温度源 ——
    val settingsScreenOffStop: String
    val settingsScreenOffStopDesc: String
    val settingsTemperatureSource: String

    /** 与 `FanNodes.temperatureSources` **顺序一一对应**；由单测守住这条对齐关系。 */
    val temperatureSourceNames: List<String>
    val screenOffStoppedNotice: String
    val controlDegradedTitle: String
    val controlDegradedHint: String

    // —— 快捷设置磁贴：风扇总开关 ——
    /**
     * 磁贴标签。**不随后状态变化**：这是总开关，标签描述"它是什么"，
     * 开/关由磁贴的点亮态表达，跟随状态改名字反而让人每次都要重读一遍。
     */
    val tileFanMaster: String
    /** 磁贴切换失败时的提示。 */
    val tileFanFailed: String

    // —— 轮询间隔与升降过渡 ——
    val settingsPollInterval: String
    val settingsPollIntervalDesc: String
    val settingsFanTransition: String
    val settingsFanTransitionDesc: String
    val settingsRangeHint: String
}

object ZhStrings : AppStrings {
    override val langIsZh = true
    override val appName = "ColdFront"
    override val cancel = "取消"
    override val delete = "删除"
    override val back = "返回"
    override val retry = "重试"
    override val close = "关闭"

    override val navHome = "风扇"
    override val navSettings = "设置"

    override val homeTitle = "风扇控制"
    override val homeFanSwitch = "风扇开关"
    override val homeFanSwitchDesc = "关闭后风扇完全停转"
    override val homeConnected = "控制已就绪"
    override val homeConnecting = "正在读取状态…"
    override val homeSmart = "智能温控"
    override val homeSmartDesc = "按本机温度自动调节转速"
    override val homeFullSpeed = "满速模式"
    override val homeFullSpeedDesc = "强制最高转速，功耗与噪音更高"
    override val homeLevel = "风扇转速"
    override val homeLevelGear = "%d 档"
    override val homeLevelPercent = "%d%%"
    override val homeSmartActiveLevel = "智能温控运行中，转速由温度曲线调节"
    override val homeTemp = "温度"
    override val telemetryDegradedTitle = "状态更新受限"
    override val telemetryDegradedHint = "部分读数已停止更新，显示值可能已过期。请运行检查确认 Root 与风扇节点状态。"
    override val homeControlFailedTitle = "控制指令未生效"
    override val homeControlFailedHint = "节点写入失败，风扇可能没有按预期变化。请运行检查确认 Root 与风扇节点状态。"

    override val homeNotActivated = "风扇控制未启用"
    override val homeNotActivatedHint = "启用前不会修改系统风扇策略；挂载过程只读取状态。"
    override val homeFailed = "无法控制本机风扇"
    override val homeFailedHint = "需要 Root 权限和可读写的风扇节点。请完成运行检查后重试。"
    override val homeActivate = "启用控制"
    override val homeRecheck = "运行检查"

    override val checkTitle = "运行检查"
    override val checkSubtitle = "确认风扇节点与传感器状态"
    override val checkModel = "本机型号"
    override val checkRoot = "Root 权限"
    override val checkNode = "风扇节点"
    override val checkTemperature = "温度读取"
    override val checkSpeed = "转速读取"
    override val checkNotification = "通知权限"
    override val checkPass = "正常"
    override val checkFail = "异常"
    override val checkSkip = "未检测"
    override val checkRunning = "检测中…"
    override val checkRetry = "重试"
    override val checkNotificationGrant = "允许通知"
    override val checkNotificationHint = "可选：用于后台调节风扇时显示常驻通知"
    override val checkSummaryReady = "必需项全部通过，可以控制本机风扇"
    override val checkSummaryNotReady = "仍有必需项未通过，风扇控制保持禁用"
    override val checkRequiredHint = "机型为信息项；通知权限为可选项，未开启不影响风扇控制。"

    override val welcomeTitle = "欢迎使用 ColdFront"
    override val welcomeSubtitle = "专为 REDMI K90 至尊版内置风扇设计"
    override val onboardingNext = "下一步"
    override val onboardingPrev = "上一步"
    override val onboardingStart = "开始使用"
    override val rootChecking = "正在检测…"
    override val rootGranted = "已获取 Root 权限"
    override val rootDenied = "未获取 Root 权限"
    override val rootHint = "风扇节点需要 Root 权限才能读写，请在授权界面选择允许。权限被拒绝时不会修改任何风扇设置。"
    override val rootRetry = "重新检测"
    override val doneTitle = "设置完成"
    override val doneReady = "一切就绪，现在可以控制本机风扇了"
    override val doneNotReady = "仍有检查未通过，风扇控制会保持禁用，可稍后重新检查"
    override val doneEnter = "开始使用"
    override val doneEnterAnyway = "仍然进入"
    override val doneClose = "完成"
    override val checkOptionalOff = "未开启（可选）"

    override val settingsTitle = "设置"
    override val settingsDynamicColor = "动态取色"
    override val settingsDynamicColorDesc = "跟随系统壁纸配色"
    override val settingsThemeMode = "主题模式"
    override val settingsPalette = "调色板"
    override val settingsPaletteTonalSpot = "Tonal Spot"
    override val settingsPaletteNeutral = "Neutral"
    override val settingsPaletteVibrant = "Vibrant"
    override val settingsPaletteExpressive = "Expressive"
    override val settingsPaletteRainbow = "Rainbow"
    override val settingsPaletteFruitSalad = "Fruit Salad"
    override val settingsPaletteMonochrome = "Monochrome"
    override val settingsPaletteFidelity = "Fidelity"
    override val settingsPaletteContent = "Content"
    override val settingsPredictiveBack = "预测性返回"
    override val settingsPredictiveBackDesc = "启用导航页面的系统返回过渡"
    override val settingsRecheck = "运行检查"
    override val settingsRecheckDesc = "重新检测 Root、风扇节点与传感器"
    override val settingsFollowSystem = "跟随系统"
    override val settingsDarkModeLight = "浅色"
    override val settingsDarkModeDark = "深色"
    override val settingsLanguage = "语言"
    override val settingsAbout = "关于"
    override val settingsAboutReport = "提交错误报告"
    override val settingsAboutProject = "上游项目主页"
    override val aboutOpenLinkFailed = "没有可用于打开此链接的应用。"
    override val storageOperationFailed = "读取或保存配置失败，请稍后重试。"

    override val serviceChannelName = "风扇控制服务"
    override val serviceChannelDesc = "智能温控常驻通知与状态"
    override val serviceAutoOn = "智能温控：开"
    override val serviceSwitchManual = "切回手动"
    override val serviceReconnect = "重新检查"
    override val serviceWaitingConfig = "等待配置…"
    override val serviceManual = "智能温控：关"
    override val serviceControlFailed = "未能确认温控指令已生效，请运行检查后重试。"
    override val serviceUnavailable = "无法启动智能温控。请打开应用完成运行检查，确认 Root 权限与风扇节点可用。"

    override val curveTitle = "温控曲线"
    override val curveDescription = "拖动节点调整温度与转速。转速随温度只会升高，不会回落。"
    override val curveBaseline = "默认曲线"
    override val curveNow = "当前"
    override val curvePresetQuiet = "静谧"
    override val curvePresetBalanced = "均衡"
    override val curvePresetPerformance = "性能"
    override val curvePresetCustom = "自定义"
    override val curvePointFormat = "节点 %d"
    override val curveTempAxis = "温度 °C"
    override val curveDutyAxis = "转速 %"
    override val curveSave = "保存"
    override val curveRevert = "放弃修改"
    override val curveSaved = "曲线已保存"
    override val curveLivePointFormat = "%.0f°C → %d%%"
    override val curveDangerTitle = "高温区转速偏低"
    override val curveDangerHintFormat = "%d°C 时仅 %d%%（默认曲线为 ≥%d%%）"
    override val curveDangerConfirmTitle = "仍然保存这条曲线？"
    override val curveDangerConfirmBody = "曲线在高温区转速偏低时，机身会更热并更早降频。风扇本身通常不会损坏——芯片有独立于风扇的热保护——但性能与手感会受影响。"
    override val curveDangerConfirmSave = "仍然保存"
    override val curveDangerConfirmBack = "返回调整"
    override val curveCorruptTitle = "曲线数据损坏"
    override val curveCorruptHint = "存储中的曲线无法解析，已恢复为默认曲线。"
    override val curveCorruptRepair = "恢复默认"

    override val settingsScreenOffStop = "息屏停转"
    override val settingsScreenOffStopDesc = "屏幕熄灭后停转以省电；充电或温度偏高时交回系统管理"
    override val settingsTemperatureSource = "温度源"
    override val temperatureSourceNames = listOf(
        "核心温度", "外壳温度", "电池温度", "充电IC温度", "闪存温度", "运存温度", "大核温度", "中核温度",
    )
    override val screenOffStoppedNotice = "息屏停转中：屏幕点亮后自动恢复曲线"
    override val settingsPollInterval = "轮询间隔"
    override val settingsPollIntervalDesc = "每隔多久读取一次温度并计算目标转速；越短越跟手，也越频繁调用 root"
    override val settingsFanTransition = "升降过渡时间"
    override val settingsFanTransitionDesc = "目标变化后在线性过渡时间内走到目标；0 ms = 立即到位"
    override val settingsRangeHint = "可用范围"
    override val controlDegradedTitle = "已交回系统管理"
    override val controlDegradedHint = "连续读不到温度，曲线已停止接管，风扇交回系统策略。请运行检查确认温度传感器可读。"

    override val tileFanMaster = "风扇控制"
    override val tileFanFailed = "风扇开关切换失败，请确认已授予 Root 权限"
}

object EnStrings : AppStrings {
    override val langIsZh = false
    override val appName = "ColdFront"
    override val cancel = "Cancel"
    override val delete = "Delete"
    override val back = "Back"
    override val retry = "Retry"
    override val close = "Close"

    override val navHome = "Fan"
    override val navSettings = "Settings"

    override val homeTitle = "Fan control"
    override val homeFanSwitch = "Fan switch"
    override val homeFanSwitchDesc = "Turning this off stops the fan completely"
    override val homeConnected = "Ready"
    override val homeConnecting = "Reading state…"
    override val homeSmart = "Smart temp control"
    override val homeSmartDesc = "Adjusts fan speed from this phone's temperature"
    override val homeFullSpeed = "Full speed"
    override val homeFullSpeedDesc = "Forces maximum speed — more power draw and noise"
    override val homeLevel = "Fan speed"
    override val homeLevelGear = "Level %d"
    override val homeLevelPercent = "%d%%"
    override val homeSmartActiveLevel = "Smart control active - speed follows the temperature curve"
    override val homeTemp = "Temperature"
    override val telemetryDegradedTitle = "Readings interrupted"
    override val telemetryDegradedHint = "Some readings stopped updating and may be out of date. Run the checks to verify root and the fan node."
    override val homeControlFailedTitle = "Command had no effect"
    override val homeControlFailedHint = "A node write failed, so the fan may not have changed. Run the checks to verify root and the fan node."

    override val homeNotActivated = "Fan control is off"
    override val homeNotActivatedHint = "Nothing changes the system fan policy until you enable control; mounting only reads state."
    override val homeFailed = "Can't control the built-in fan"
    override val homeFailedHint = "Root access and a readable fan node are required. Run the checks, then retry."
    override val homeActivate = "Enable control"
    override val homeRecheck = "Run checks"

    override val checkTitle = "Checks"
    override val checkSubtitle = "Verify the fan node and sensors"
    override val checkModel = "Device model"
    override val checkRoot = "Root access"
    override val checkNode = "Fan node"
    override val checkTemperature = "Temperature read"
    override val checkSpeed = "Speed read"
    override val checkNotification = "Notification permission"
    override val checkPass = "OK"
    override val checkFail = "Failed"
    override val checkSkip = "Not checked"
    override val checkRunning = "Checking…"
    override val checkRetry = "Retry"
    override val checkNotificationGrant = "Allow notifications"
    override val checkNotificationHint = "Optional: shows a persistent notification while controlling the fan in the background"
    override val checkSummaryReady = "All required checks passed - you can control the built-in fan"
    override val checkSummaryNotReady = "Required checks still failing - fan control stays disabled"
    override val checkRequiredHint = "The model row is informational; notifications are optional and never block fan control."

    override val welcomeTitle = "Welcome to ColdFront"
    override val welcomeSubtitle = "Built for the REDMI K90 Ultra's internal fan"
    override val onboardingNext = "Next"
    override val onboardingPrev = "Back"
    override val onboardingStart = "Get started"
    override val rootChecking = "Checking…"
    override val rootGranted = "Root access granted"
    override val rootDenied = "Root access not granted"
    override val rootHint = "The fan node needs root access to read and write. Allow it in the prompt; if you decline, no fan setting is touched."
    override val rootRetry = "Check again"
    override val doneTitle = "Setup complete"
    override val doneReady = "Everything is ready - you can control the built-in fan now"
    override val doneNotReady = "Some checks failed. Fan control stays disabled; you can run the checks again later."
    override val doneEnter = "Get started"
    override val doneEnterAnyway = "Continue anyway"
    override val doneClose = "Done"
    override val checkOptionalOff = "Off (optional)"

    override val settingsTitle = "Settings"
    override val settingsDynamicColor = "Dynamic color"
    override val settingsDynamicColorDesc = "Follow system wallpaper"
    override val settingsThemeMode = "Theme mode"
    override val settingsPalette = "Palette"
    override val settingsPaletteTonalSpot = "Tonal Spot"
    override val settingsPaletteNeutral = "Neutral"
    override val settingsPaletteVibrant = "Vibrant"
    override val settingsPaletteExpressive = "Expressive"
    override val settingsPaletteRainbow = "Rainbow"
    override val settingsPaletteFruitSalad = "Fruit Salad"
    override val settingsPaletteMonochrome = "Monochrome"
    override val settingsPaletteFidelity = "Fidelity"
    override val settingsPaletteContent = "Content"
    override val settingsPredictiveBack = "Predictive back"
    override val settingsPredictiveBackDesc = "Enable system back transitions for app navigation"
    override val settingsRecheck = "Run checks"
    override val settingsRecheckDesc = "Re-check root, the fan node and the sensors"
    override val settingsFollowSystem = "Follow system"
    override val settingsDarkModeLight = "Light"
    override val settingsDarkModeDark = "Dark"
    override val settingsLanguage = "Language"
    override val settingsAbout = "About"
    override val settingsAboutReport = "Report an issue"
    override val settingsAboutProject = "Upstream project"
    override val aboutOpenLinkFailed = "No app is available to open this link."
    override val storageOperationFailed = "Couldn't read or save settings. Please try again."

    override val serviceChannelName = "Fan control service"
    override val serviceChannelDesc = "Persistent notification and status for smart temp control"
    override val serviceAutoOn = "Smart control: on"
    override val serviceSwitchManual = "Switch to manual"
    override val serviceReconnect = "Run checks"
    override val serviceWaitingConfig = "Waiting for config…"
    override val serviceManual = "Smart control: off"
    override val serviceControlFailed = "Couldn't confirm the smart-control command. Run the checks and retry."
    override val serviceUnavailable = "Can't start smart control. Open the app, run the checks and confirm root access plus the fan node."

    override val curveTitle = "Fan curve"
    override val curveDescription = "Drag the points to tune temperature and speed. Speed can only rise with temperature, never fall."
    override val curveBaseline = "Default curve"
    override val curveNow = "Now"
    override val curvePresetQuiet = "Quiet"
    override val curvePresetBalanced = "Balanced"
    override val curvePresetPerformance = "Performance"
    override val curvePresetCustom = "Custom"
    override val curvePointFormat = "Point %d"
    override val curveTempAxis = "Temp °C"
    override val curveDutyAxis = "Speed %"
    override val curveSave = "Save"
    override val curveRevert = "Discard"
    override val curveSaved = "Curve saved"
    override val curveLivePointFormat = "%.0f°C → %d%%"
    override val curveDangerTitle = "Low speed at high temperature"
    override val curveDangerHintFormat = "At %d°C only %d%% (the default curve uses ≥%d%%)"
    override val curveDangerConfirmTitle = "Save this curve anyway?"
    override val curveDangerConfirmBody = "Holding the fan back at high temperature makes the phone hotter and throttle sooner. The fan itself is unlikely to be damaged — the chip has thermal protection independent of the fan — but performance and feel will suffer."
    override val curveDangerConfirmSave = "Save anyway"
    override val curveDangerConfirmBack = "Keep editing"
    override val curveCorruptTitle = "Curve data was damaged"
    override val curveCorruptHint = "The stored curve could not be read, so the default curve was restored."
    override val curveCorruptRepair = "Restore default"

    override val settingsScreenOffStop = "Stop when screen off"
    override val settingsScreenOffStopDesc = "Stop the fan with the screen off to save power; the system takes over while charging or when it runs warm"
    override val settingsTemperatureSource = "Temperature source"
    override val temperatureSourceNames = listOf(
        "CPU core", "Chassis", "Battery", "Charging IC", "Storage", "RAM", "Big cores", "Mid cores",
    )
    override val screenOffStoppedNotice = "Stopped while the screen is off; the curve resumes when you turn it back on"
    override val settingsPollInterval = "Refresh interval"
    override val settingsPollIntervalDesc = "How often the curve reads temperature and computes the target speed; shorter reacts faster but calls root more often"
    override val settingsFanTransition = "Ramp duration"
    override val settingsFanTransitionDesc = "How long to move linearly to a changed target; 0 ms jumps straight to the target"
    override val settingsRangeHint = "Allowed range"
    override val controlDegradedTitle = "Handed back to the system"
    override val controlDegradedHint = "The temperature became unreadable, so the curve stopped driving the fan and the system took over. Run the checks to confirm the sensor is readable."

    override val tileFanMaster = "Fan control"
    override val tileFanFailed = "Could not toggle the fan; make sure root access is granted"
}

/**
 * 当前生效的字符串表。
 *
 * @param override 用户在设置里选的语言："zh" / "en" 强制，其余（包括 "system"）跟随系统 locale。
 * 工程文案全在这张 Kotlin 表里、没有 strings.xml，所以覆盖语言不需要 per-app locale，
 * 换表即可。（副作用：系统级文案如权限弹框仍跟随系统语言。）
 */
fun stringsFor(locale: Locale, override: String? = null): AppStrings = when (override) {
    "zh" -> ZhStrings
    "en" -> EnStrings
    else -> if (locale.language.equals("zh", ignoreCase = true)) ZhStrings else EnStrings
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { error("AppStrings not provided") }

@Composable
fun rememberStrings(override: String? = null): AppStrings =
    stringsFor(LocalConfiguration.current.locales[0] ?: Locale.ROOT, override)
