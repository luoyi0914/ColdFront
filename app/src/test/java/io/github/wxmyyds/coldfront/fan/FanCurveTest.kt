package io.github.wxmyyds.coldfront.fan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线纯逻辑（插值 / 校验 / JSON），纯 JVM，不需要设备。
 *
 * 这里覆盖两件事：
 * 1. **默认曲线与移植前逐字一致** —— 换了实现但不能换行为，否则老用户升级后手感会变；
 * 2. **结构性约束真的挡住不良构曲线** —— 上游允许拖成乱序、区间查找落空后返回 0，
 *    导致风扇静默停转；本实现必须让那条路径**不可达**，而不是"大概率不会发生"。
 */
class FanCurveTest {

    private fun curve(vararg points: Pair<Int, Int>) =
        FanCurve(points.map { CurvePoint(it.first, it.second) })

    // ───────────────────── 插值 ─────────────────────

    @Test
    fun `default curve preserves the legacy hard-coded behaviour exactly`() {
        val default = FanCurve.DEFAULT
        // 旧实现 calcPwmByTemp / curvePwmForTemp 的断点值
        assertEquals(0, default.pwmFor(30))
        assertEquals(30, default.pwmFor(35))
        assertEquals(70, default.pwmFor(40))
        assertEquals(80, default.pwmFor(45))
        assertEquals(90, default.pwmFor(50))
        assertEquals(99, default.pwmFor(55))
        assertEquals(99, default.pwmFor(60))
        assertEquals(99, default.pwmFor(90))
    }

    @Test
    fun `interpolates linearly between breakpoints`() {
        val default = FanCurve.DEFAULT
        assertEquals(12, default.pwmFor(32)) // 30→0 与 35→30 之间，比例 0.4
        assertEquals(46, default.pwmFor(37)) // 35→30 与 40→70 之间，比例 0.4
        assertEquals(74, default.pwmFor(42))
    }

    @Test
    fun `truncates toward zero like the legacy implementation`() {
        // 50→90 与 55→99 之间每度 1.8%：51°C 得 91.8，旧实现截断成 91 而不是四舍五入成 92。
        assertEquals(91, FanCurve.DEFAULT.pwmFor(51))
        assertEquals(93, FanCurve.DEFAULT.pwmFor(52))
    }

    @Test
    fun `clamps below the first and above the last breakpoint`() {
        val default = FanCurve.DEFAULT
        assertEquals(0, default.pwmFor(FanCurve.MIN_TEMP_C))
        assertEquals(0, default.pwmFor(0))
        assertEquals(99, default.pwmFor(100))
        assertEquals(99, default.pwmFor(500))
    }

    @Test
    fun `returns the exact breakpoint value at a breakpoint`() {
        val default = FanCurve.DEFAULT
        default.points.forEach { point ->
            assertEquals(point.pwmPercent, default.pwmFor(point.tempC))
        }
    }

    // ───────────────────── 结构性约束 ─────────────────────

    @Test
    fun `rejects an unordered curve instead of silently computing zero`() {
        val unordered = listOf(
            CurvePoint(30, 0),
            CurvePoint(50, 60),
            CurvePoint(40, 90), // 温度回退 —— 上游在这儿会落空并返回 0
            CurvePoint(60, 99),
        )
        val violation = FanCurve.validate(unordered)
        assertEquals(CurveViolation.Kind.TEMP_NOT_ASCENDING, violation?.kind)
        assertEquals(2, violation?.index)
        // 关键回归：不良构曲线根本构造不出来，所以"返回 0 静默停转"这条路径不可达。
        assertNull(FanCurve.of(unordered))
        assertThrows(IllegalArgumentException::class.java) { FanCurve(unordered) }
    }

    @Test
    fun `rejects duplicated temperatures`() {
        val duplicated = listOf(CurvePoint(40, 10), CurvePoint(40, 20), CurvePoint(60, 30))
        assertEquals(CurveViolation.Kind.TEMP_NOT_ASCENDING, FanCurve.validate(duplicated)?.kind)
    }

    @Test
    fun `rejects a decreasing duty even though temperatures ascend`() {
        val decreasing = listOf(CurvePoint(40, 50), CurvePoint(50, 20), CurvePoint(60, 30))
        val violation = FanCurve.validate(decreasing)
        assertEquals(CurveViolation.Kind.DUTY_DECREASING, violation?.kind)
        assertEquals(1, violation?.index)
    }

    @Test
    fun `rejects temperatures outside the axis`() {
        assertEquals(
            CurveViolation.Kind.TEMP_OUT_OF_RANGE,
            FanCurve.validate(listOf(CurvePoint(19, 0), CurvePoint(40, 10)))?.kind,
        )
        assertEquals(
            CurveViolation.Kind.TEMP_OUT_OF_RANGE,
            FanCurve.validate(listOf(CurvePoint(40, 0), CurvePoint(101, 10)))?.kind,
        )
    }

    @Test
    fun `rejects duty outside zero to ninety-nine`() {
        assertEquals(
            CurveViolation.Kind.DUTY_OUT_OF_RANGE,
            FanCurve.validate(listOf(CurvePoint(40, 0), CurvePoint(50, 100)))?.kind,
        )
        assertEquals(
            CurveViolation.Kind.DUTY_OUT_OF_RANGE,
            FanCurve.validate(listOf(CurvePoint(40, -1), CurvePoint(50, 10)))?.kind,
        )
    }

    @Test
    fun `rejects point counts outside the supported range`() {
        assertEquals(
            CurveViolation.Kind.TOO_FEW_POINTS,
            FanCurve.validate(listOf(CurvePoint(40, 10)))?.kind,
        )
        val tooMany = (0..FanCurve.MAX_POINTS).map { CurvePoint(30 + it, 10) }
        assertEquals(CurveViolation.Kind.TOO_MANY_POINTS, FanCurve.validate(tooMany)?.kind)
    }

    // ───────────────────── 预设 ─────────────────────

    @Test
    fun `every preset is structurally valid`() {
        CurvePreset.entries.forEach { preset ->
            val curve = FanCurve.presetCurve(preset)
            assertNull("预设 $preset 不良构", FanCurve.validate(curve.points))
            assertEquals(FanCurve.EDITOR_POINT_COUNT, curve.points.size)
        }
    }

    @Test
    fun `no preset triggers the danger warning`() {
        // 静谧刻意比默认线安静；若危险判定写成"低于基线"，选它就会满屏警告。
        CurvePreset.entries.forEach { preset ->
            assertNull("预设 $preset 触发了危险判定", FanCurve.presetCurve(preset).firstDangerousTemp())
        }
    }

    @Test
    fun `default curve equals the balanced preset`() {
        assertEquals(CurvePreset.BALANCED, FanCurve.DEFAULT.matchingPreset())
        assertEquals(FanCurve.DEFAULT, FanCurve.BASELINE)
    }

    @Test
    fun `a custom curve matches no preset`() {
        assertNull(curve(30 to 0, 60 to 40, 90 to 99).matchingPreset())
    }

    // ───────────────────── 危险判定 ─────────────────────

    @Test
    fun `flags a curve that stays low at high temperature`() {
        val risky = curve(30 to 0, 90 to 10)
        // 55°C 起就命中"≥55 且 <20%"这一条
        assertEquals(55, risky.firstDangerousTemp())
    }

    @Test
    fun `danger rule needs both a high temperature and a low duty`() {
        assertTrue(FanCurve.isDangerous(60, 49))
        assertTrue(FanCurve.isDangerous(55, 19))
        assertTrue(!FanCurve.isDangerous(60, 50))
        assertTrue(!FanCurve.isDangerous(55, 20))
        // 低温区转速低不算危险：风扇本来就该在凉的时候停转
        assertTrue(!FanCurve.isDangerous(30, 0))
        assertTrue(!FanCurve.isDangerous(54, 0))
    }

    // ───────────────────── JSON ─────────────────────

    @Test
    fun `json round-trips a custom curve`() {
        val original = curve(25 to 0, 45 to 33, 70 to 99)
        val restored = FanCurveJson.parse(FanCurveJson.encode(original))
        assertEquals(original, restored)
        assertEquals(original.points, restored.points)
    }

    @Test
    fun `encoded json carries a version field`() {
        val json = FanCurveJson.encode(FanCurve.DEFAULT)
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains(FanCurveJson.VERSION.toString()))
    }

    @Test
    fun `absent storage means default curve and is not a failure`() {
        // 首次运行没有曲线是正常状态，不该被当成数据损坏去打扰用户。
        assertEquals(CurveLoad.Loaded(FanCurve.DEFAULT), FanCurveJson.decode(null))
        assertEquals(CurveLoad.Loaded(FanCurve.DEFAULT), FanCurveJson.decode(""))
        assertEquals(CurveLoad.Loaded(FanCurve.DEFAULT), FanCurveJson.decode("   "))
    }

    @Test
    fun `corrupt storage falls back to default and reports it`() {
        val loads = listOf(
            "{", // 坏 JSON
            "[]", // 顶层不是对象
            """{"version":1}""", // 缺 points
            """{"points":[]}""", // 缺 version
            """{"version":1,"points":[{"tempC":30,"pwmPercent":0}]}""", // 只有一个点
            """{"version":1,"points":[{"tempC":50,"pwmPercent":10},{"tempC":40,"pwmPercent":20}]}""",
            """{"version":99,"points":[]}""", // 未来版本：宁可回退也不猜语义
            """{"version":1,"points":[{"tempC":30.5,"pwmPercent":0},{"tempC":40,"pwmPercent":10}]}""",
            """{"version":1,"points":[]} trailing""", // 尾随垃圾
        )
        loads.forEach { stored ->
            val load = FanCurveJson.decode(stored)
            assertTrue("应判定为损坏：$stored", load is CurveLoad.FellBack)
            assertEquals(FanCurve.DEFAULT, load.curve)
        }
    }

    @Test
    fun `strict parse never returns a default for corrupt input`() {
        assertThrows(java.io.IOException::class.java) { FanCurveJson.parse("{") }
        assertThrows(java.io.IOException::class.java) {
            FanCurveJson.parse("""{"version":1,"points":[{"tempC":30,"pwmPercent":0},{"tempC":40,"pwmPercent":100}]}""")
        }
    }

    @Test
    fun `parses a curve missing the version as corrupt rather than guessing`() {
        val load = FanCurveJson.decode("""{"points":[{"tempC":30,"pwmPercent":0},{"tempC":40,"pwmPercent":10}]}""")
        assertNotNull(load)
        assertTrue(load is CurveLoad.FellBack)
    }
}
