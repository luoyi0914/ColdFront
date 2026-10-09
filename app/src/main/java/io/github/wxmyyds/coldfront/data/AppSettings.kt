package io.github.wxmyyds.coldfront.data

import io.github.wxmyyds.coldfront.fan.CurveLoad
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.fan.FanTuning

/**
 * One validated DataStore snapshot. Absence of a snapshot is represented by null in the UI.
 *
 * 新字段一律追加在末尾并带默认值：调用方存在按位置传参的写法，默认值必须与
 * 「偏好为空」时仓储产出的值一致，否则相等性会被无声破坏。
 */
data class AppSettings(
    val dynamicColor: Boolean,
    val darkMode: String,
    val palette: String,
    val predictiveBack: Boolean,
    val appLanguage: String,
    val onboardingCompleted: Boolean = false,
    /**
     * 智能温控曲线的读取结果。
     *
     * 刻意保留 [CurveLoad] 而不是只给一条曲线：**「存储里没有曲线」与「存储里的曲线坏了」
     * 必须可区分** —— 前者是首次运行的正常状态，后者必须告知用户，不能混成一句"没有曲线"。
     */
    val smartCurveLoad: CurveLoad = CurveLoad.Loaded(FanCurve.DEFAULT),
    /** 曲线基准温度源下标，索引 [io.github.wxmyyds.coldfront.fan.FanNodes.temperatureSources]。 */
    val temperatureSource: Int = 0,
    /** 息屏停转开关。默认关闭：后台改变硬件行为必须来自显式授权。 */
    val screenOffStopFan: Boolean = false,
    /**
     * 温控轮询间隔（毫秒），自由输入，合法区间见
     * [io.github.wxmyyds.coldfront.fan.FanTuning.POLL_INTERVAL_RANGE]。
     *
     * 它只决定「最坏多少毫秒之后才发现温度变了」，与下面的过渡时间**不再相乘**。
     */
    val pollIntervalMs: Int = FanTuning.DEFAULT_POLL_INTERVAL_MS,
    /**
     * 升降过渡时间（毫秒）：目标转速变化后，用多少毫秒从当前占空比线性走到目标。
     *
     * 0 = 一步到位（默认），合法区间见 [io.github.wxmyyds.coldfront.fan.FanTuning.TRANSITION_RANGE]。
     */
    val fanTransitionMs: Int = FanTuning.DEFAULT_TRANSITION_MS,
) {
    /** 始终是一条**良构**曲线：存储缺失或损坏时这里是默认曲线。 */
    val smartCurve: FanCurve get() = smartCurveLoad.curve

    /** 存储里的曲线存在但无法解析，已回退默认 —— 界面必须告知用户。 */
    val smartCurveCorrupt: Boolean get() = smartCurveLoad is CurveLoad.FellBack
}
