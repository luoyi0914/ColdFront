package io.github.wxmyyds.coldfront.ui.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsTest {
    @Test
    fun `explicit language overrides system locale`() {
        assertSame(EnStrings, stringsFor(Locale.CHINA, "en"))
        assertSame(ZhStrings, stringsFor(Locale.US, "zh"))
    }

    @Test
    fun `system and unknown preferences retain existing fallback`() {
        assertSame(ZhStrings, stringsFor(Locale.TAIWAN, "system"))
        assertSame(EnStrings, stringsFor(Locale.FRANCE, "system"))
        assertSame(ZhStrings, stringsFor(Locale.CHINA, "invalid"))
    }

    @Test
    fun `percentage formatting is localized in both tables`() {
        assertEquals("42%", ZhStrings.homeLevelPercent.format(Locale.ROOT, 42))
        assertEquals("42%", EnStrings.homeLevelPercent.format(Locale.ROOT, 42))
    }

    @Test
    fun `fan wording no longer borrows external-cooler semantics`() {
        // 本 fork 只控制内置风扇：不应再出现「散热器 / 破坏神 / 灯效」等外置散热器措辞。
        val forbidden = listOf("散热器", "破坏神", "灯效", "RGB", "cooler", "Destruction God")
        val fanFields = listOf(
            ZhStrings.homeTitle, ZhStrings.homeFanSwitch, ZhStrings.homeFanSwitchDesc,
            ZhStrings.homeSmart, ZhStrings.homeSmartDesc, ZhStrings.homeFullSpeed,
            ZhStrings.homeFullSpeedDesc, ZhStrings.homeLevel, ZhStrings.homeSmartActiveLevel,
            EnStrings.homeTitle, EnStrings.homeFanSwitch, EnStrings.homeFanSwitchDesc,
            EnStrings.homeSmart, EnStrings.homeSmartDesc, EnStrings.homeFullSpeed,
            EnStrings.homeFullSpeedDesc, EnStrings.homeLevel, EnStrings.homeSmartActiveLevel,
        )
        for (value in fanFields) {
            for (word in forbidden) {
                assertFalse("「$value」仍包含外置散热器措辞「$word」", value.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun `guidance never promises a working fan while required checks fail`() {
        // 文案本身不能暗示「一定成功」：完成页必须同时具备就绪与未就绪两种说法。
        assertTrue(ZhStrings.doneReady.isNotBlank())
        assertTrue(ZhStrings.doneNotReady.isNotBlank())
        assertTrue(EnStrings.doneReady.isNotBlank())
        assertTrue(EnStrings.doneNotReady.isNotBlank())
        // 未就绪时的按钮文案必须与就绪时不同，避免把「仍然进入」说成「开始使用」。
        assertTrue(ZhStrings.doneEnterAnyway != ZhStrings.doneEnter)
        assertTrue(EnStrings.doneEnterAnyway != EnStrings.doneEnter)
    }
}
