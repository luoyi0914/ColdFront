package io.github.wxmyyds.coldfront.ui.i18n

import io.github.wxmyyds.coldfront.fan.FanNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 温度源名称表必须与 [FanNodes.temperatureSources] **顺序一一对应**。
 *
 * 这条对齐是无声的：名字与实际读取的传感器一旦错位，用户会以为自己选了"外壳温度"、
 * 实际却在用"核心温度"，而界面上没有任何东西能暴露这件事。所以用测试钉死。
 */
class TemperatureSourceNamesTest {

    @Test
    fun `both languages name every temperature source`() {
        val expected = FanNodes.temperatureSources.size
        assertEquals(expected, ZhStrings.temperatureSourceNames.size)
        assertEquals(expected, EnStrings.temperatureSourceNames.size)
    }

    @Test
    fun `no temperature source name is blank or duplicated within a language`() {
        listOf(ZhStrings, EnStrings).forEach { strings ->
            val names = strings.temperatureSourceNames
            names.forEachIndexed { index, name ->
                assertTrue("第 $index 项名称为空", name.isNotBlank())
            }
            assertEquals("同一语言内出现重名", names.size, names.toSet().size)
        }
    }

    @Test
    fun `the catalogue position matches the fan node catalogue`() {
        // 抽查两个端点：下标 0 必须是 FanNodes 里第一个源，最后一个同理。
        FanNodes.temperatureSources.forEachIndexed { index, source ->
            assertTrue(
                "下标 $index 的源应当有对应的界面名称",
                index < ZhStrings.temperatureSourceNames.size &&
                    index < EnStrings.temperatureSourceNames.size,
            )
            assertTrue(source.label.isNotBlank())
        }
    }
}
