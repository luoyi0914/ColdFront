package io.github.wxmyyds.coldfront.domain

import java.util.UUID

/**
 * RGB 灯效(逆向自官方 App,写入 0x1013 的命令格式 [mode][R][G][B]):
 * 1=炫彩 2=全彩呼吸 3=单色呼吸 4=常亮 6=关闭。
 * 官方另有 0x05=场景灯效,仅由场景联动(`sendScenarioCmd`)下发,8 Pro 灯效页不提供;
 * 本 App 不暴露该选项,0x05 因此按未知处理。
 */
enum class LightEffect(val code: Byte, val labelZh: String, val labelEn: String) {
    COLORFUL(0x01, "炫彩", "Colorful"),
    /** UI 上叫「呼吸」。协议里是不带颜色字节的全彩呼吸。 */
    BREATH_FULLCOLOR(0x02, "呼吸", "Breathing"),
    /**
     * 官方协议的单色呼吸。主菜单通过 [uiEffect] 合并到「呼吸」，
     * 子菜单、草稿、预览和命令必须保留此真实变体，不能归一成全彩。
     */
    BREATH_SINGLE(0x03, "单色呼吸", "Breathing (Single)"),
    ALWAYS_BRIGHT(0x04, "常亮", "Always On"),
    OFF(0x06, "关闭", "Off"),
    ;

    /** 设备回报/旧档案里的单色呼吸，统一按「呼吸」呈现 */
    val uiEffect: LightEffect
        get() = if (this == BREATH_SINGLE) BREATH_FULLCOLOR else this

    companion object {
        fun fromCode(code: Byte): LightEffect? = entries.firstOrNull { it.code == code }

        /** 主菜单项；单色/全彩在呼吸子菜单中选择。 */
        val selectable: List<LightEffect> =
            listOf(ALWAYS_BRIGHT, BREATH_FULLCOLOR, COLORFUL, OFF)
    }
}

/**
 * RGB 配置。
 * @property effect 灯效
 * @property red/green/blue 颜色分量（0–255）
 */
data class RGBConfig(
    val effect: LightEffect,
    val red: Int = 255,
    val green: Int = 0,
    val blue: Int = 0,
) {
    init {
        require(red in 0..255) { "red 越界：$red" }
        require(green in 0..255) { "green 越界：$green" }
        require(blue in 0..255) { "blue 越界：$blue" }
    }

    companion object {
        /**
         * ALWAYS_BRIGHT/BREATH_SINGLE carry a colour and need all three colour bytes: a
         * mode-only reply is rejected rather than inventing a colour. Every other mode parses
         * from the mode byte alone and keeps the previously known colour.
         */
        fun fromNotification(value: ByteArray, previous: RGBConfig?): RGBConfig? {
            val effect = value.firstOrNull()?.let(LightEffect::fromCode) ?: return null
            val colourBearing = effect == LightEffect.ALWAYS_BRIGHT || effect == LightEffect.BREATH_SINGLE
            if (colourBearing && value.size < 4) return null
            val base = previous ?: RGBConfig(effect)
            return if (colourBearing) {
                RGBConfig(effect, value[1].toInt() and 0xFF, value[2].toInt() and 0xFF, value[3].toInt() and 0xFF)
            } else base.copy(effect = effect)
        }
    }

    /** 序列化为 [effect][R][G][B] 4 字节命令;炫彩/全彩呼吸不带颜色字节(实测 App 同样置零) */
    fun toCommand(): ByteArray = when (effect) {
        LightEffect.COLORFUL, LightEffect.BREATH_FULLCOLOR, LightEffect.OFF ->
            byteArrayOf(effect.code, 0, 0, 0)
        else ->
            byteArrayOf(effect.code, red.toByte(), green.toByte(), blue.toByte())
    }
}

/** RGB 命令的 GATT 写入状态。SENT 仅表示写入回调成功，不代表设备再次回读。 */
enum class RgbWriteStatus {
    WRITING,
    SENT,
    FAILED,
}

data class RgbWriteState(
    val requestId: Long,
    val config: RGBConfig,
    val status: RgbWriteStatus,
    /** Last parsed RGB report at platform write dispatch, excluding all later notifications. */
    val readbackRevisionAtWrite: Long = 0L,
) {
    /** Ignore obsolete completions after a newer request has superseded this one. */
    fun completed(completedRequestId: Long, success: Boolean): RgbWriteState =
        if (completedRequestId == requestId && status == RgbWriteStatus.WRITING) {
            copy(status = if (success) RgbWriteStatus.SENT else RgbWriteStatus.FAILED)
        } else this
}

/** 风扇模式 */
enum class FanMode(val labelZh: String, val labelEn: String) {
    OFF("关闭", "Off"),
    MANUAL("手动", "Manual"),
    AUTO("自动", "Auto"),
}

/** BLE 连接状态 */
enum class ConnectionState(val labelZh: String, val labelEn: String) {
    DISCONNECTED("已断开", "Disconnected"),
    SCANNING("扫描中", "Scanning"),
    CONNECTING("连接中", "Connecting"),
    DISCOVERING("发现服务中", "Discovering Services"),
    CONNECTED("已连接", "Connected"),
    FAILED("连接失败", "Failed"),
}

/**
 * 散热器实时状态（UI 与 Service 共享）。
 */
data class CoolerLiveState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val temperatureC: Float? = null,
    val fanPercent: Int = 0,
    /** Preserve the device byte: percentage rounding must not change the displayed gear. */
    val fanRaw: Int? = null,
    /** Pending slider target; distinct from the last acknowledged/device-reported speed. */
    val pendingFanPercent: Int? = null,
    val fanMode: FanMode = FanMode.OFF,
    val rgb: RGBConfig? = null,
    /** Increases for every parsed light report, even when its value is unchanged. */
    val rgbRevision: Long = 0L,
    val deviceType: CoolerDeviceType? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val rssi: Int? = null,
    /** 风扇转速(101C/1015上报, RPM) */
    val fanRpm: Int? = null,
    /** 功率(101D/1015上报, W) */
    val powerW: Int? = null,
    /** 散热总开关(1011): 2=开 3=关 */
    val coolingOn: Boolean = false,
    /** 智能温控(1018): 设备自主温控 */
    val smartOn: Boolean = false,
    /**
     * 当前温控曲线对应的预设下标（0 静谧 / 1 均衡 / 2 性能），自定义曲线为 -1。
     *
     * 控制中心面板与软件内曲线页共用存储里的同一条曲线，因此这个值既决定面板高亮哪一档，
     * 也让「面板换档」与「软件里换曲线」永远显示同一个事实。
     */
    val curvePreset: Int = -1,
    /** 破坏神/Boost 超频(1017) */
    val boostOn: Boolean = false,
    /** 过冷/冷凝保护(101F bit2) */
    val overcoldOn: Boolean = false,
    /**
     * 供电功率限档(0x1015 标签 0x7)。仅 8 Pro 上报该字段:取值是官方档位索引 0–8,
     * 8(`CoolerBleConstants.FAN_LIMIT_UNLIMITED_8_PRO`)表示不限档。null = 尚未上报。
     */
    val fanLimit: Int? = null,
    /** Distinguish reconnects even when lifecycle collection skips intermediate states. */
    val connectionSessionId: Long = 0L,
    val capabilities: CoolerCapabilities = CoolerCapabilities(),
    /** Configuration values reported by this connection; never carried across sessions. */
    val confirmedConfiguration: Set<UUID> = emptySet(),
    /** A timed-out lane made telemetry uncertain. A new connection resets this warning. */
    val telemetryDegraded: Boolean = false,
    /** 最近一次控制写入（节点写入或接管）失败；UI 需要如实提示，不能假装成功。 */
    val controlError: Boolean = false,
    /**
     * 靠本应用**主动停转**而停着的风扇（目前只有息屏停转）。
     *
     * 与"用户把风扇开关关掉"是两回事：那是用户的决定，这是自动化替他做的决定，
     * 界面必须能说清风扇为什么停着。
     */
    val screenOffStopped: Boolean = false,
    /**
     * 曲线已失效并降级交还系统（温度长期读不到等）。
     *
     * 与 [telemetryDegraded] 刻意分开：后者只说"数据可能过期"，这里说的是
     * **控制权已经交出去了**。常驻场景下用户不在看界面，不区分就会以为风扇还归曲线管。
     */
    val controlDegraded: Boolean = false,
) {
    val isConnected: Boolean get() = connection == ConnectionState.CONNECTED
    val temperatureText: String get() = temperatureC?.let { "%.1f°C".format(it) } ?: "--"

    fun hasConfirmedConfiguration(uuid: UUID): Boolean = uuid in confirmedConfiguration

    val coolingAllowsControl: Boolean
        get() = !capabilities.hasCoolingSwitch ||
            (hasConfirmedConfiguration(CoolerBleConstants.COOLING_SWITCH_UUID) && coolingOn)

    val manualLevelEnabled: Boolean
        get() = isConnected && hasConfirmedConfiguration(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID) &&
            capabilities.fanControl && coolingAllowsControl && !smartOn && !boostOn

    /** 设备上报了低于"不限档"的功耗限档 → 充电器供电功率不足。 */
    val powerLimited: Boolean
        get() = deviceType?.generation == 8 &&
            (fanLimit ?: CoolerBleConstants.FAN_LIMIT_UNLIMITED_8_PRO) <
            CoolerBleConstants.FAN_LIMIT_UNLIMITED_8_PRO

    /** 供电功率不足时允许的最高风扇 raw;null = 不受限 */
    val fanRawLimit: Int?
        get() = fanLimit?.takeIf { powerLimited }?.let(CoolerBleConstants::fanLimitToRaw8Pro)

    /** 供电功率不足时允许的最高档位(1–8);null = 不受限 */
    val fanGearLimit: Int?
        get() = fanLimit?.takeIf { powerLimited }?.let(CoolerBleConstants::maxGearForFanLimit8Pro)

    /**
     * Pending intent wins until acknowledged; otherwise use the unrounded device byte.
     * 供电功率不足时钳到限档:官方把滑条压回 `maxSelectableProgress` 并下发该档位,
     * 所以显示值与之后实际可选的档位保持一致。
     */
    val fanGear: Int?
        get() {
            val type = deviceType?.takeIf { it.generation == 8 } ?: return null
            val raw = pendingFanPercent?.let { CoolerBleConstants.percentageToRaw(it, type) }
                ?: fanRaw ?: CoolerBleConstants.percentageToRaw(fanPercent, type)
            val gear = CoolerBleConstants.rawToGear8Pro(raw)
            return fanGearLimit?.let { minOf(gear, it) } ?: gear
        }
}
