package io.github.wxmyyds.coldfront.fan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 息屏停转的判定。纯 JVM，不需要设备。
 *
 * 覆盖四件事：
 * 1. **不进入停转的条件必须都兜住** —— 开关关闭、非智能温控、屏幕亮、充电、温度读不到；
 * 2. **迟滞真的成立** —— 停转态在迟滞带内不得反复跳；
 * 3. **亮屏一定回到 [ScreenPhase.ACTIVE]** —— 否则风扇会带着停转状态醒不过来；
 * 4. **凉就停转，不"交还系统"** —— 交还系统会让驱动把转速拉到 ~12000 rpm，
 *    正是用户报告的「息屏后转速先飙高」故障，所以低于阈值必须走 [ScreenPhase.STOPPED]。
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

    @Test
    fun `recovers from handed back to stopped once the temperature drops`() {
        // 交还态（充电拔掉 / 温度恢复可读）在凉下来后应能重新停转，
        // 否则一次交还就永远回不到省电的停转状态。
        assertEquals(
            ScreenPhase.STOPPED,
            next(temperatureC = 30, current = ScreenPhase.HANDED_BACK),
        )
    }

    // ───────────────────── 双阈值 ─────────────────────

    @Test
    fun `stops only below the lower threshold`() {
        assertEquals(ScreenPhase.STOPPED, next(temperatureC = ScreenOffPolicy.STOP_BELOW_C - 1))
        // 恰好等于下限不进入停转：进入条件保守（交给系统，不主动停）。
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = ScreenOffPolicy.STOP_BELOW_C))
    }

    @Test
    fun `stays stopped anywhere inside the hysteresis band`() {
        // 停转态一旦建立，只要没到下阈值退出条件就**持续保持停转**。
        // 这是本次修复的核心：绝不能在带内滑向 HANDED_BACK（那会让风扇飙到 ~12000 rpm）。
        (ScreenOffPolicy.STOP_BELOW_C until ScreenOffPolicy.RESUME_ABOVE_C).forEach { temperature ->
            assertEquals(
                "停转态在 ${temperature}°C 不应退出、更不应交还系统",
                ScreenPhase.STOPPED,
                next(temperatureC = temperature, current = ScreenPhase.STOPPED),
            )
        }
    }

    @Test
    fun `the hysteresis band is non-empty and ordered`() {
        assertTrue(ScreenOffPolicy.STOP_BELOW_C < ScreenOffPolicy.RESUME_ABOVE_C)
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
    fun `holding a warm curve below the upper threshold keeps the fan stopped`() {
        // 一个典型回合：熄屏、凉 → 停转；随后转热但未越过退出阈值 → **仍然停转**；
        // 只有越过高阈值才交还系统（热兜底）。
        val stopped = next(temperatureC = 38)
        assertEquals(ScreenPhase.STOPPED, stopped)
        assertEquals(ScreenPhase.STOPPED, next(temperatureC = 45, current = stopped))
        assertEquals(ScreenPhase.HANDED_BACK, next(temperatureC = 52, current = ScreenPhase.STOPPED))
    }
}
