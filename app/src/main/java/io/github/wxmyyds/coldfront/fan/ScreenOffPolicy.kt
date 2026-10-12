package io.github.wxmyyds.coldfront.fan

/**
 * 息屏期间风扇应处于的相位。
 *
 * 用三个相位而不是一个布尔，是因为「息屏停转」和「交给系统」是**两件不同的事**：
 * 前者要我们保持接管并写 0，后者要把控制权交出去。混成一个开关就无法表达
 * "凉时我们停、热时交给系统"这个双阈值行为。
 */
enum class ScreenPhase {
    /** 屏幕点亮，或不满足息屏停转条件：曲线照常运行。 */
    ACTIVE,

    /** 息屏且温度够低：收回控制权并写 0，真正停转（静音、省电）。 */
    STOPPED,

    /** 息屏（或温度偏高）：把控制权交还系统，由系统热保护兜底。 */
    HANDED_BACK,
}

/**
 * 息屏停转的判定。**纯函数**，因此可以在纯 JVM 单测里覆盖。
 *
 * ## 为什么不再用「双阈值 + 交还系统」
 *
 * 早期版本在息屏且温度偏高（≥ [STOP_BELOW_C]）时走「交还系统」，本意是让系统热保护兜底。
 * 实机（K90 Ultra，Android 17）验证发现这条路径**会造成用户可见的故障**：
 * 交还系统要写 `target_level=1`，而该写入会让 pwm-fan 驱动**立即接管并强制拉高占空比**
 * （实测 pwm 被驱动改写为 51、转速冲到 ~12000 rpm），此后驱动一直保持这个转速，
 * 并不会随时间降到 0。用户开启「息屏停转」后看到的正是「一息屏转速先飙到最高、
 * 然后缓慢回落」——那个"缓慢回落"其实是风扇惯性，最终并不停转。
 *
 * 实测对照（同一台机器、同一起点 60% 占空比）：
 * - 交还系统：+1s 13629 rpm → +4s 11998 rpm → +10s 仍 11984 rpm（**永不停转**）
 * - 保持接管写 0：+1s 即 **2 rpm**，并稳定保持
 *
 * 因此现在息屏一律走 [ScreenPhase.STOPPED]：保持接管并把占空比写 0，1 秒内完成停转。
 *
 * ## 安全边界并没有丢失
 * - 温度**读不到**时仍回 [ScreenPhase.HANDED_BACK]：拿不到温度就敢停风扇，等于闭眼赌它不热。
 * - 充电时也仍交还系统：充电是最大发热场景，不该由我们主动停转。
 * - 息屏期间轮询照常进行（[FanTuning.SCREEN_OFF_POLL_INTERVAL_MS]），一旦温度升到
 *   [RESUME_ABOVE_C] 就退出停转、交还系统，热保护仍然生效。
 *
 * 保留 [ScreenPhase.HANDED_BACK] 这个相位本身，是因为它仍被「温度读不到」「充电」
 * 以及连续读温失败的降级路径使用。
 */
object ScreenOffPolicy {

    /**
     * 息屏保持停转的最高温度。
     *
     * 达到或超过此温度就退出停转、交还系统（见 [next]）。这是停转与"热兜底"之间的分界。
     */
    const val RESUME_ABOVE_C = 48

    /**
     * 低于此温度才允许息屏停转。
     *
     * 注意这里**不再是**停滞带回差的上半部分：因为进入停转后不再"交还系统"，
     * 而是持续保持在 [ScreenPhase.STOPPED]，直到温度升到 [RESUME_ABOVE_C]。
     */
    const val STOP_BELOW_C = 42

    /**
     * 计算下一个相位。
     *
     * @param enabled 用户是否开启「息屏停转」。
     * @param smartActive 当前是否处于智能温控。**手动 / 满速不参与**：那是用户的直接指令，
     *   自动化不应该覆盖它（也与"满速与智能温控互斥"的既有设计一致）。
     * @param charging 是否在充电。充电是最大的发热场景，此时一律交给系统，不主动停转。
     * @param temperatureC 当前基准温度；null 表示读不到 → 交还系统。
     * @param current 当前相位。
     */
    fun next(
        enabled: Boolean,
        smartActive: Boolean,
        screenInteractive: Boolean,
        charging: Boolean,
        temperatureC: Int?,
        current: ScreenPhase,
    ): ScreenPhase {
        if (!enabled || !smartActive || screenInteractive) return ScreenPhase.ACTIVE
        if (charging) return ScreenPhase.HANDED_BACK
        val temperature = temperatureC ?: return ScreenPhase.HANDED_BACK
        // 已停转：只有明确转热才退出，否则维持停转（避免阈值附近抖动）。
        if (current == ScreenPhase.STOPPED) {
            return if (temperature >= RESUME_ABOVE_C) ScreenPhase.HANDED_BACK else ScreenPhase.STOPPED
        }
        // 未停转：凉就停转；偏热则交给系统热保护，不主动停。
        return if (temperature < STOP_BELOW_C) ScreenPhase.STOPPED else ScreenPhase.HANDED_BACK
    }
}
