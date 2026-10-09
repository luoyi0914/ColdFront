package io.github.wxmyyds.coldfront.ui

import io.github.wxmyyds.coldfront.fan.CurvePoint
import io.github.wxmyyds.coldfront.fan.CurvePreset
import io.github.wxmyyds.coldfront.fan.FanCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线编辑器草稿状态。纯 JVM，不需要设备。
 *
 * 重点覆盖**拖动钳制**：它是"编辑器里永远不存在非法曲线"这条不变式的唯一守卫，
 * 而上游正是在这里缺了一行约束，导致乱序曲线落盘后风扇静默停转。
 */
class CurveEditorStateTest {

    private fun state(curve: FanCurve = FanCurve.DEFAULT) = CurveEditorState.of(curve)

    // ───────────────────── X 钳制 ─────────────────────

    @Test
    fun `dragging cannot push a point past its left neighbour`() {
        val original = state()
        val moved = original.movePoint(1, tempC = 10, pwmPercent = 50)
        // 邻居 0 在 30°C，最小间隔 1°C → 不能低于 31
        assertEquals(31, moved.draft[1].tempC)
    }

    @Test
    fun `dragging cannot push a point past its right neighbour`() {
        val original = state()
        val moved = original.movePoint(1, tempC = 100, pwmPercent = 50)
        // 邻居 2 在 40°C → 不能高于 39
        assertEquals(39, moved.draft[1].tempC)
    }

    @Test
    fun `the first and last points are bounded by the axis instead of neighbours`() {
        val first = state().movePoint(0, tempC = -100, pwmPercent = 0)
        assertEquals(FanCurve.MIN_TEMP_C, first.draft.first().tempC)

        val last = state().movePoint(FanCurve.DEFAULT.points.lastIndex, tempC = 999, pwmPercent = 99)
        assertEquals(FanCurve.MAX_TEMP_C, last.draft.last().tempC)
    }

    @Test
    fun `a dragged curve stays structurally valid even at the tightest spacing`() {
        // 三个相邻断点、间隔都取最小值：这里最容易出现"钳制上下界反了"的崩溃。
        val tight = FanCurve(listOf(CurvePoint(40, 10), CurvePoint(41, 20), CurvePoint(42, 30)))
        val editor = CurveEditorState.of(tight)

        // 温度只剩 41 这一个合法位置；占空比 15 落在邻居 [10, 30] 之内，原样保留。
        val inRange = editor.movePoint(1, tempC = 41, pwmPercent = 15)
        assertNull(FanCurve.validate(inRange.draft))
        assertEquals(41, inRange.draft[1].tempC)
        assertEquals(15, inRange.draft[1].pwmPercent)

        // 占空比越界才被邻居钳制回来。
        val clamped = editor.movePoint(1, tempC = 41, pwmPercent = 0)
        assertNull(FanCurve.validate(clamped.draft))
        assertEquals(10, clamped.draft[1].pwmPercent)
    }

    @Test
    fun `an out-of-range index is ignored instead of throwing`() {
        val original = state()
        assertEquals(original, original.movePoint(99, tempC = 50, pwmPercent = 50))
        assertEquals(original, original.movePoint(-1, tempC = 50, pwmPercent = 50))
    }

    // ───────────────────── Y 钳制（单调不减） ─────────────────────

    @Test
    fun `dragging cannot make the curve decrease`() {
        val original = state()
        // 第 3 个点在 45°C/80%，邻居 2 是 40°C/70% → Y 不能低于 70
        val moved = original.movePoint(3, tempC = 45, pwmPercent = 5)
        assertEquals(70, moved.draft[3].pwmPercent)
    }

    @Test
    fun `dragging cannot make the curve exceed the next point`() {
        val original = state()
        // 邻居 4 是 50°C/90% → Y 不能高于 90
        val moved = original.movePoint(3, tempC = 45, pwmPercent = 99)
        assertEquals(90, moved.draft[3].pwmPercent)
    }

    @Test
    fun `every legal drag keeps the whole draft valid`() {
        // 扫一遍比较粗暴，但这条不变式太重要：任意点、任意极端值都不得产出非法曲线。
        val extremes = listOf(-999, -1, 0, 20, 41, 55, 99, 100, 999)
        FanCurve.DEFAULT.points.indices.forEach { index ->
            extremes.forEach { temp ->
                extremes.forEach { duty ->
                    val moved = state().movePoint(index, temp, duty)
                    assertNull(
                        "点 $index 拖到 ($temp, $duty) 后曲线不良构",
                        FanCurve.validate(moved.draft),
                    )
                }
            }
        }
    }

    @Test
    fun `moving a point to where it already is returns the same instance`() {
        val original = state()
        val point = original.draft[2]
        assertEquals(original, original.movePoint(2, point.tempC, point.pwmPercent))
    }

    // ───────────────────── 脏标记与预设 ─────────────────────

    @Test
    fun `a freshly opened editor is not dirty`() {
        assertFalse(state().isDirty)
    }

    @Test
    fun `a moved point marks the draft dirty and revert clears it`() {
        val moved = state().movePoint(2, tempC = 42, pwmPercent = 75)
        assertTrue(moved.isDirty)
        assertFalse(moved.revert().isDirty)
        assertEquals(FanCurve.DEFAULT.points, moved.revert().draft)
    }

    @Test
    fun `the default curve reports itself as the balanced preset`() {
        assertEquals(CurvePreset.BALANCED, state().preset)
    }

    @Test
    fun `selecting a preset replaces the draft but keeps dirty tracking towards the origin`() {
        val selected = state().selectPreset(CurvePreset.QUIET)
        assertEquals(CurvePreset.QUIET, selected.preset)
        assertEquals(FanCurve.presetCurve(CurvePreset.QUIET).points, selected.draft)
        assertTrue(selected.isDirty)
        // 放弃修改回到的是**打开时的曲线**，不是上一个预设
        assertEquals(FanCurve.DEFAULT.points, selected.revert().draft)
    }

    @Test
    fun `a hand-edited curve reports no preset`() {
        assertNull(state().movePoint(1, tempC = 36, pwmPercent = 33).preset)
    }

    // ───────────────────── 危险判定 ─────────────────────

    @Test
    fun `no preset is flagged as dangerous`() {
        CurvePreset.entries.forEach { preset ->
            assertNull("预设 $preset 被标成危险", state().selectPreset(preset).dangerousFrom)
        }
    }

    @Test
    fun `a curve kept low at high temperature reports where the danger starts`() {
        // 30°C→0% 到 90°C→10%：从 55°C 起命中"≥55 且 <20%"
        val flat = CurveEditorState.of(FanCurve(listOf(CurvePoint(30, 0), CurvePoint(90, 10))))
        assertEquals(55, flat.dangerousFrom)
        assertTrue(flat.isDangerousAt(70))
        assertFalse(flat.isDangerousAt(30))
    }
}
