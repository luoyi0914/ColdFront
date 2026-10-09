package io.github.wxmyyds.coldfront.fan

import kotlin.math.roundToInt

/**
 * 温控响应速度的**唯一权威**：轮询间隔与升降过渡时间，以及两者的取值边界。
 *
 * ## 两个参数是独立的
 *
 * 旧实现把「轮询间隔」与「调速步进（每轮最多动 N%）」绑在一起，爬升时间等于
 * `(99 / 步进) × 间隔` —— 用户画一条陡曲线，实际响应会滞后几十秒。现在拆开：
 *
 * - **轮询间隔**：多久读一次温度。它只决定「最坏多少毫秒之后才发现温度变了」。
 * - **升降过渡时间**：发现目标变化后，用多少毫秒从当前占空比线性走到目标。**默认 0 = 一步到位**，
 *   也就是温度一涨，下一轮轮询直接把转速写到曲线目标值，不再有"每秒只准涨 5%"的限速。
 *
 * 过渡是按**实际经过时间**推进的，只在轮询节拍上落地：过渡时间小于轮询间隔时，
 * 中间没有机会插步（每写一次节点都要起一个 `su` 进程），因此等价于一步到位。
 *
 * 放在这里而不是散在 `SettingsRepository` 与设置页各写一遍，是为了让**校验、默认值、
 * 界面可用范围、单测共用一个来源**：仓储用它兜住非法落盘值，界面用它显示范围，
 * 单测用它钉住「默认值是合法值」。
 *
 * 纯逻辑、无 Android 依赖，因此可以单测。
 */
object FanTuning {

    // ───────────────────── 轮询间隔 ─────────────────────

    /**
     * 轮询间隔下限 200ms。
     *
     * 每一轮都要起一个 `su` 进程读节点：500ms 一轮约合 17 万次/天，200ms 一轮约合 43 万次/天。
     * 再密下去对电量和 root 框架（KernelSU/Magisk）都是负担，所以给出下限而不是完全放开。
     */
    const val MIN_POLL_INTERVAL_MS = 200

    /** 上限 5 秒：再慢就会让"高温了风扇还没起来"变得可感知。 */
    const val MAX_POLL_INTERVAL_MS = 5_000

    /** 默认 1 秒：用户定的手感。 */
    const val DEFAULT_POLL_INTERVAL_MS = 1_000

    /**
     * 息屏期间的轮询间隔。
     *
     * 息屏时曲线不执行（见 [ScreenPhase]），轮询的唯一用途是驱动停转双阈值判定与高温兜底，
     * 因此可以放慢：常驻 24 小时 15 秒一次约合 5760 次 `su` 唤起，对电量与 root 框架都是
     * 实打实的开销。
     */
    const val SCREEN_OFF_POLL_INTERVAL_MS = 15_000L

    // ───────────────────── 升降过渡 ─────────────────────

    /** 过渡时间下限：0 = 不做过渡，目标一变就写到位（默认，也是"直接提升到目标转速"）。 */
    const val MIN_TRANSITION_MS = 0

    /** 上限 10 秒：比这更长的过渡与"温控跟得上温度"这个目标相冲突。 */
    const val MAX_TRANSITION_MS = 10_000

    /** 默认 0：不插值，一步到位。 */
    const val DEFAULT_TRANSITION_MS = 0

    /** 合法区间，供设置页显示可用范围。 */
    val POLL_INTERVAL_RANGE = MIN_POLL_INTERVAL_MS..MAX_POLL_INTERVAL_MS

    /** 合法区间，供设置页显示可用范围。 */
    val TRANSITION_RANGE = MIN_TRANSITION_MS..MAX_TRANSITION_MS

    // ───────────────────── 规范化 ─────────────────────

    /** 非法或缺失一律回落到默认，绝不把未校验的值写进轮询循环的 `delay()`。 */
    fun pollIntervalOrDefault(value: Int?): Int =
        value?.takeIf { it in POLL_INTERVAL_RANGE } ?: DEFAULT_POLL_INTERVAL_MS

    /** 非法或缺失一律回落到默认（0 = 一步到位）。 */
    fun transitionMsOrDefault(value: Int?): Int =
        value?.takeIf { it in TRANSITION_RANGE } ?: DEFAULT_TRANSITION_MS

    /**
     * 息屏轮询间隔：**不得比亮屏间隔还勤**。
     *
     * 亮屏上限（5 秒）远小于 [SCREEN_OFF_POLL_INTERVAL_MS]，这里取 max 是为了
     * 将来放宽上限时不会出现「息屏比亮屏采样更频繁」这种反直觉的组合。
     */
    fun screenOffPollInterval(pollIntervalMs: Int): Long =
        maxOf(SCREEN_OFF_POLL_INTERVAL_MS, pollIntervalMs.toLong())

    // ───────────────────── 过渡插值 ─────────────────────

    /**
     * 一次过渡中，经过 [elapsedMs] 后应该落在哪个占空比。
     *
     * 纯函数：`FanManager` 只负责喂「起点、目标、经过时间、过渡时长」，判定与取整都在这里，
     * 因此不需要 Android Context 就能单测。
     *
     * - [transitionMs] <= 0 或 [elapsedMs] >= [transitionMs] → 直接返回 [toPwm]（一步到位）。
     * - [elapsedMs] <= 0 → 返回 [fromPwm]（刚起步，还没有走）。
     * - 其余按时间比例线性插值，四舍五入。
     *
     * 返回值的**单调性**是有意保证的：时间只增不减时占空比不会来回跳。
     */
    fun rampPwm(fromPwm: Int, toPwm: Int, elapsedMs: Long, transitionMs: Int): Int {
        if (transitionMs <= 0 || elapsedMs >= transitionMs) return toPwm
        if (elapsedMs <= 0) return fromPwm
        val progress = elapsedMs.toFloat() / transitionMs
        return (fromPwm + (toPwm - fromPwm) * progress).roundToInt().coerceIn(0, 99)
    }
}
