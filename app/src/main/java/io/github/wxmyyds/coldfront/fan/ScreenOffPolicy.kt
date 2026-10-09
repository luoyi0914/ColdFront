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
 * 息屏停转的双阈值判定。**纯函数**，因此可以在纯 JVM 单测里覆盖。
 *
 * ## 为什么是双阈值
 * 单阈值会在阈值附近抖动：温度在 42°C 上下浮动时，风扇会反复在"停转 / 交给系统"之间跳，
 * 听感上就是忽起忽停。所以进入停转用低阈值（[STOP_BELOW_C]），退出用高阈值
 * （[RESUME_ABOVE_C]），中间 6°C 是迟滞带。
 *
 * ## 为什么默认走「交还系统」
 * 息屏期间系统自己管风扇**自带热保护**，因此进程被杀不再等于"风扇永久停转"。
 * 只有确认"凉"才收回控制权去停转 —— 这是唯一会让风扇停在 0% 且系统不接管的状态，
 * 所以进入条件必须保守。
 *
 * ## 安全优先的兜底
 * 温度读不到时**不进入停转**（回 [ScreenPhase.HANDED_BACK]）：拿不到温度就敢停风扇，
 * 等于闭着眼睛赌它不热。
 */
object ScreenOffPolicy {

    /** 低于此温度才允许收回控制权并停转。 */
    const val STOP_BELOW_C = 42

    /** 达到此温度就交还系统（与 [STOP_BELOW_C] 构成 6°C 迟滞带）。 */
    const val RESUME_ABOVE_C = 48

    /**
     * 计算下一个相位。
     *
     * @param enabled 用户是否开启「息屏停转」。
     * @param smartActive 当前是否处于智能温控。**手动 / 满速不参与**：那是用户的直接指令，
     *   自动化不应该覆盖它（也与"满速与智能温控互斥"的既有设计一致）。
     * @param charging 是否在充电。充电是最大的发热场景，此时一律交给系统，不主动停转。
     * @param temperatureC 当前基准温度；null 表示读不到。
     * @param current 当前相位，用于迟滞判定。
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
        if (current == ScreenPhase.STOPPED) {
            // 已停转：只有明确转热才退出，否则维持在迟滞带内不动作。
            return if (temperature >= RESUME_ABOVE_C) ScreenPhase.HANDED_BACK else ScreenPhase.STOPPED
        }
        return if (temperature < STOP_BELOW_C) ScreenPhase.STOPPED else ScreenPhase.HANDED_BACK
    }
}
