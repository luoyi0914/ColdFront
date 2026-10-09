package io.github.wxmyyds.coldfront.fan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 息屏停转的双阈值判定。纯 JVM，不需要设备。
 *
 * 覆盖三件事：
 * 1. **不进入停转的条件必须都兜住** —— 开关关闭、非智能温控、屏幕亮、充电、温度读不到；
 * 2. **迟滞真的成立** —— 迟滞带内不得在两个相位之间反复跳；
 * 3. **亮屏一定回到 [ScreenPhase.ACTIVE]** —— 否则风扇会带着停转状态醒不过来。
 */
class ScreenOffPolicyTest {

    /** 屏幕熄灭、未充电、智能温控运行中的常规起点。 */
    private fun next(
        temperatureC: Int?,
        current: ScreenPhase = ScreenPhase.ACTIVE,
        enabled: Boolean = true,
        smartActive: Boolean = true,
        screenInteractive: Boolean = false,
        charging: Boolean = false,
    ) = ScreenOffPolicy.next(
        enabled = enabled,
        smartActive = smartActive,
        screenInteractive = screenInteractive,
        charging = charging,
        temperatureC = temperatureC,
        current = current,
    )

    // ───────────────────── 不进入停转的条件 ─────────────────────

    @Test
    fun `stays active when the toggle is off`() {
        assertEquals(ScreenPhase.ACTIVE, next(temperatureC = 25, enabled = false))
    }

    @Test
    fun `stays active outside smart mode`() {
        // 手动滑条与满速是用户的直接指令，自动化不该覆盖它们。
        assertEquals(ScreenPhase.ACTIVE, next(temperatureC = 25, smartActive = false))
    }

    @Test
    fun `returns to active as soon as the screen is on`() {
        ScreenPhase.entries.forEach { phase ->
            assertEquals(
                "屏幕点亮必须无条件回到 ACTIVE",
                ScreenPhase.ACTIVE,
                next(temperatureC = 60, current = phase, screenInteractive = true),
            )
        }
    }

    @Test
    fun `hands back to the system while charging rather than stopping`() {
        // 充电是最大的发热场景：让系统管，不主动停转。
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = 25, charging = true))
        // 即使已经停转，插上电也应该交还系统。
        assertEquals(
            ScreenPhase.HANDED_BACK,
            next(temperatureC = 25, current = ScreenPhase.STOPPED, charging = true),
        )
    }

    @Test
    fun `never stops when the temperature is unreadable`() {
        // 拿不到温度就敢停风扇，等于闭着眼睛赌它不热。
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = null))
        assertEquals(
            ScreenPhase.HANDED_BACK,
            next(temperatureC = null, current = ScreenPhase.STOPPED),
        )
    }

    // ───────────────────── 双阈值 ─────────────────────

    @Test
    fun `stops only below the lower threshold`() {
        assertEquals(ScreenPhase.STOPPED, next(temperatureC = ScreenOffPolicy.STOP_BELOW_C - 1))
        // 恰好等于下限不进入停转：进入条件保守。
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = ScreenOffPolicy.STOP_BELOW_C))
    }

    @Test
    fun `resumes at the upper threshold once stopped`() {
        assertEquals(ScreenPhase.STOPPED, next(temperatureC = ScreenOffPolicy.RESUME_ABOVE_C - 1, current = ScreenPhase.STOPPED))
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = ScreenOffPolicy.RESUME_ABOVE_C, current = ScreenPhase.STOPPED))
    }

    @Test
    fun `the hysteresis band is non-empty and ordered`() {
        assertTrue(ScreenOffPolicy.STOP_BELOW_C < ScreenOffPolicy.RESUME_ABOVE_C)
    }

    @Test
    fun `does not flap anywhere inside the hysteresis band`() {
        // 迟滞带内：停住的保持停住，交出的保持交出 —— 两边的判定必须相反，
        // 否则温度在带内浮动时风扇会忽起忽停。
        (ScreenOffPolicy.STOP_BELOW_C until ScreenOffPolicy.RESUME_ABOVE_C).forEach { temperature ->
            assertEquals(
                "停转态在 ${temperature}°C 不应退出",
                ScreenPhase.STOPPED,
                next(temperatureC = temperature, current = ScreenPhase.STOPPED),
            )
            assertEquals(
                "交还态在 ${temperature}°C 不应收回",
                ScreenPhase.HANDED_BACK,
                next(temperatureC = temperature, current = ScreenPhase.HANDED_BACK),
            )
        }
    }

    @Test
    fun `repeated evaluation inside the band is stable`() {
        // 模拟连续几轮轮询都落在带内：相位不得发生任何变化。
        var phase = ScreenPhase.STOPPED
        listOf(43, 46, 44, 47, 42, 45).forEach { temperature ->
            val nextPhase = next(temperatureC = temperature, current = phase)
            assertEquals("带内轮询不应改变相位（$temperature°C）", phase, nextPhase)
            phase = nextPhase
        }
    }

    @Test
    fun `holding a warm curve below the lower threshold is what stops the fan`() {
        // 一个典型回合：熄屏、凉 → 停转；随后转热越过高阈值 → 交还系统。
        val stopped = next(temperatureC = 38)
        assertEquals(ScreenPhase.STOPPED, stopped)
        assertEquals(ScreenPhase.STOPPED, next(temperatureC = 45, current = stopped))
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = 52, current = ScreenPhase.STOPPED))
    }
}
