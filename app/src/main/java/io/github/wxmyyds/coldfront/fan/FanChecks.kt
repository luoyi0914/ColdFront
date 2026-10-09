package io.github.wxmyyds.coldfront.fan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 批量遥测的解析结果；每个字段 null 都表示「这一项没读到」，而不是读到了 0。 */
data class FanSnapshot(
    val temperatureRaw: Int?,
    val rpmRaw: Int?,
    val pwmRaw: Int?,
    val levelRaw: Int?,
)

/** 一次批量读取的结果：`executed` 表示 `su` 命令本身是否成功执行。 */
data class FanSnapshotRead(val snapshot: FanSnapshot, val executed: Boolean)

internal val EMPTY_SNAPSHOT = FanSnapshot(null, null, null, null)

/**
 * 解析批量遥测输出（`T:`/`R:`/`P:`/`L:` 前缀行）。
 * 纯函数，便于单测覆盖「部分读取失败」「空输出」「非数字」等情况。
 */
internal fun parseFanSnapshot(output: String): FanSnapshot {
    var temperature: Int? = null
    var rpm: Int? = null
    var pwm: Int? = null
    var level: Int? = null
    for (line in output.lineSequence()) {
        val marker = line.indexOf(':')
        if (marker <= 0) continue
        val value = line.substring(marker + 1).trim().toIntOrNull() ?: continue
        when (line[0]) {
            'T' -> temperature = value
            'R' -> rpm = value
            'P' -> pwm = value
            'L' -> level = value
        }
    }
    return FanSnapshot(temperature, rpm, pwm, level)
}

// ───────────────────── 运行检查模型 ─────────────────────

enum class FanCheckId {
    MODEL,
    ROOT,
    FAN_NODE,
    TEMPERATURE,
    SPEED,
    NOTIFICATION,
}

enum class FanCheckState { PASS, FAIL, SKIP }

data class FanCheckOutcome(val id: FanCheckId, val state: FanCheckState, val detail: String = "")

/**
 * 首次引导 / 「运行检查」的报告。
 *
 * [requiredPassed] 只统计**控制风扇所必需**的四项（Root、风扇节点、温度、转速）；
 * 机型是信息项，通知权限是可选项 —— 未开启通知不应阻止用户使用风扇控制，
 * 因此通知项在未授权时报告 [FanCheckState.SKIP] 而不是 FAIL：
 * UI 上「可选且未开启」必须和「必需项失败」看起来不一样，否则引导页会一直显示 5/6 加红色感叹号。
 */
data class FanCheckReport(val outcomes: List<FanCheckOutcome>) {
    fun outcome(id: FanCheckId): FanCheckOutcome? = outcomes.firstOrNull { it.id == id }

    fun stateOf(id: FanCheckId): FanCheckState? = outcome(id)?.state

    val requiredPassed: Boolean get() = requiredPassedCount == REQUIRED_CHECKS.size

    /** 必需项通过数，用于「n/总数」的圆环显示（只数必需项）。 */
    val requiredPassedCount: Int
        get() = REQUIRED_CHECKS.count { stateOf(it) == FanCheckState.PASS }

    val passedCount: Int get() = outcomes.count { it.state == FanCheckState.PASS }

    val totalCount: Int get() = outcomes.size

    companion object {
        val REQUIRED_CHECKS = listOf(
            FanCheckId.ROOT,
            FanCheckId.FAN_NODE,
            FanCheckId.TEMPERATURE,
            FanCheckId.SPEED,
        )
    }
}

/**
 * 检查用的只读探针。抽出接口是为了能在 JVM 单测里替换成假实现 ——
 * 设备侧的真实实现必须**只读**，不得写任何节点。
 */
interface FanProbe {
    suspend fun rootAvailable(requestPrompt: Boolean): Boolean
    suspend fun marketName(): String
    suspend fun fanSupported(): Boolean
    suspend fun temperatureC(): Int?
    suspend fun speedRpm(): Int?
}

/** 走 root sysfs 的真实探针（全部为只读命令）。 */
class RootFanProbe(private val temperatureSource: Int = FanNodes.DEFAULT_TEMPERATURE_SOURCE) : FanProbe {
    override suspend fun rootAvailable(requestPrompt: Boolean): Boolean = FanNodes.probeRoot(requestPrompt)

    // 读机型名要起 su 进程（阻塞调用），必须离开主线程。
    override suspend fun marketName(): String =
        withContext(Dispatchers.IO) { FanNodes.readMarketName() }

    override suspend fun fanSupported(): Boolean = FanNodes.isFanSupported()

    override suspend fun temperatureC(): Int? = FanNodes.readTemperatureC(temperatureSource)

    override suspend fun speedRpm(): Int? = FanNodes.readRealSpeed()
}

/**
 * 运行一次只读检查。
 *
 * Root 不可用时不继续探测节点，并且明确标记为 [FanCheckState.SKIP] ——
 * 「没权限检查」和「检查了但失败」是两种不同的状态，UI 需要区分提示。
 *
 * 每个探针都各自 `runCatching`：任何一个抛出异常（例如 `su` 二进制缺失）都只让该项判失败，
 * 不能中断整轮检查 —— 否则引导页会永远停在「检测中」。

 * @param onProgress 每得出一个结果就回调一次当前报告，供引导页逐项刷新。
 */
suspend fun runFanChecks(
    probe: FanProbe,
    notificationGranted: Boolean,
    requestRootPrompt: Boolean,
    onProgress: (List<FanCheckOutcome>) -> Unit = {},
): FanCheckReport {
    val outcomes = mutableListOf<FanCheckOutcome>()

    fun add(outcome: FanCheckOutcome) {
        outcomes += outcome
        onProgress(outcomes.toList())
    }

    val model = runCatching { probe.marketName() }.getOrNull().orEmpty()
    add(FanCheckOutcome(FanCheckId.MODEL, if (model.isNotBlank()) FanCheckState.PASS else FanCheckState.FAIL, model))

    val root = runCatching { probe.rootAvailable(requestRootPrompt) }.getOrDefault(false)
    add(FanCheckOutcome(FanCheckId.ROOT, if (root) FanCheckState.PASS else FanCheckState.FAIL))
    if (!root) {
        add(FanCheckOutcome(FanCheckId.FAN_NODE, FanCheckState.SKIP))
        add(FanCheckOutcome(FanCheckId.TEMPERATURE, FanCheckState.SKIP))
        add(FanCheckOutcome(FanCheckId.SPEED, FanCheckState.SKIP))
    } else {
        val supported = runCatching { probe.fanSupported() }.getOrDefault(false)
        add(
            FanCheckOutcome(
                FanCheckId.FAN_NODE,
                if (supported) FanCheckState.PASS else FanCheckState.FAIL,
                FanNodes.nodePath,
            ),
        )
        val temperature = if (supported) runCatching { probe.temperatureC() }.getOrNull() else null
        add(
            FanCheckOutcome(
                FanCheckId.TEMPERATURE,
                if (temperature != null) FanCheckState.PASS else FanCheckState.FAIL,
                temperature?.let { "$it °C" }.orEmpty(),
            ),
        )
        val speed = if (supported) runCatching { probe.speedRpm() }.getOrNull() else null
        add(
            FanCheckOutcome(
                FanCheckId.SPEED,
                if (speed != null) FanCheckState.PASS else FanCheckState.FAIL,
                speed?.let { "$it RPM" }.orEmpty(),
            ),
        )
    }

    // 通知是可选项：未开启报告为 SKIP（不是失败），也不计入必需项。
    add(
        FanCheckOutcome(
            FanCheckId.NOTIFICATION,
            if (notificationGranted) FanCheckState.PASS else FanCheckState.SKIP,
        ),
    )

    return FanCheckReport(outcomes)
}
