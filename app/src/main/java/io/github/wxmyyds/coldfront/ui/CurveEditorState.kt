package io.github.wxmyyds.coldfront.ui

import io.github.wxmyyds.coldfront.fan.CurvePoint
import io.github.wxmyyds.coldfront.fan.CurvePreset
import io.github.wxmyyds.coldfront.fan.FanCurve

/**
 * 曲线编辑器的草稿状态。纯逻辑，可在纯 JVM 单测里覆盖（项目没有 Robolectric）。
 *
 * ## 不变式：`draft` 任何时刻都是良构曲线
 * 拖动被钳制在「左右邻居 + 轴范围」之内，所以**编辑器里不存在"拖出一个非法状态、
 * 保存时才发现"的窗口**。校验失败只可能来自磁盘上的旧数据（见 [FanCurveJson]）。
 *
 * `origin` 是打开编辑器时的那条曲线，用来判断脏标记与「放弃修改」。
 */
data class CurveEditorState(
    val draft: List<CurvePoint>,
    val origin: FanCurve,
) {

    /** 草稿对应的良构曲线。钳制保证了不变式，[FanCurve.of] 不会返回 null。 */
    val curve: FanCurve get() = FanCurve.of(draft) ?: origin

    val isDirty: Boolean get() = draft != origin.points

    /** 草稿当前等于哪个预设；自定义时为 null。 */
    val preset: CurvePreset? get() = curve.matchingPreset()

    /** 曲线首个命中危险判定的温度；null = 没有命中。用于图上着色与保存确认。 */
    val dangerousFrom: Int? get() = curve.firstDangerousTemp()

    /**
     * 第 [index] 个断点允许的温度区间（闭区间）。
     *
     * 上下界被左右邻居各让出 [FanCurve.MIN_TEMP_GAP_C]，两端则受轴范围约束。
     * 暴露出来是给精调滑条用的：滑条范围直接用这个区间，用户就**拖不出**非法位置，
     * 而不是拖出去再被弹回来。
     */
    fun tempBounds(index: Int): IntRange {
        val lower = draft.getOrNull(index - 1)
        val upper = draft.getOrNull(index + 1)
        val min = lower?.tempC?.plus(FanCurve.MIN_TEMP_GAP_C) ?: FanCurve.MIN_TEMP_C
        val max = upper?.tempC?.minus(FanCurve.MIN_TEMP_GAP_C) ?: FanCurve.MAX_TEMP_C
        return min..max
    }

    /** 第 [index] 个断点允许的占空比区间（闭区间），即左右邻居之间，保持曲线单调不减。 */
    fun dutyBounds(index: Int): IntRange {
        val lower = draft.getOrNull(index - 1)
        val upper = draft.getOrNull(index + 1)
        val min = lower?.pwmPercent ?: FanCurve.MIN_DUTY
        val max = upper?.pwmPercent ?: FanCurve.MAX_DUTY
        return min..max
    }

    /**
     * 把第 [index] 个断点拖到 ([tempC], [pwmPercent])，并按结构约束钳制。
     *
     * **X** 夹在左右邻居之间（留 [FanCurve.MIN_TEMP_GAP_C] 的最小间隔）与轴范围内；
     * **Y** 夹在左右邻居的占空比之间，从而保持整条曲线单调不减。
     *
     * 这正是上游缺的那一行约束：上游允许把 X 拖到邻居左边，区间查找落空后返回 0，
     * 风扇**静默停转**而界面还显示"智能温控运行中"。
     */
    fun movePoint(index: Int, tempC: Int, pwmPercent: Int): CurveEditorState {
        if (index !in draft.indices) return this
        val moved = CurvePoint(
            tempC = tempC.coerceIn(tempBounds(index)),
            pwmPercent = pwmPercent.coerceIn(dutyBounds(index)),
        )
        if (moved == draft[index]) return this
        return copy(draft = draft.toMutableList().also { it[index] = moved })
    }

    /** 套用预设。`origin` 不变 —— 脏标记仍以"打开时的曲线"为基准。 */
    fun selectPreset(preset: CurvePreset): CurveEditorState =
        copy(draft = FanCurve.presetCurve(preset).points.toList())

    /** 放弃修改，回到打开编辑器时的曲线。 */
    fun revert(): CurveEditorState = copy(draft = origin.points.toList())

    /** 给定温度下，草稿曲线是否命中危险判定。供运行时提示使用。 */
    fun isDangerousAt(tempC: Int): Boolean = FanCurve.isDangerous(tempC, curve.pwmFor(tempC))

    companion object {
        fun of(curve: FanCurve): CurveEditorState = CurveEditorState(curve.points.toList(), curve)
    }
}
