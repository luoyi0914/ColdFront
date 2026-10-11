package io.github.wxmyyds.coldfront.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.data.AppSettings
import io.github.wxmyyds.coldfront.fan.FanTuning
import io.github.wxmyyds.coldfront.ui.component.RowIcon
import io.github.wxmyyds.coldfront.ui.component.PageScaffold
import io.github.wxmyyds.coldfront.ui.component.SegmentedDropdownRow
import io.github.wxmyyds.coldfront.ui.component.SegmentedNumberRow
import io.github.wxmyyds.coldfront.ui.component.SegmentedGroup
import io.github.wxmyyds.coldfront.ui.component.SegmentedRow
import io.github.wxmyyds.coldfront.ui.component.SegmentedSwitchRow
import io.github.wxmyyds.coldfront.ui.i18n.LocalStrings
import io.github.wxmyyds.coldfront.data.PaletteStyles

/**
 * 设置页(MD3E 分段选项列表):
 * - 外观组 = 动态取色开关行 + 「调色板」「主题模式」下拉行；
 * - 通用组 = 预测性返回；语言与外观同组；
 * - 诊断组 = 「运行检查」入口（只读探测 Root / 风扇节点 / 传感器）；
 * - 关于入口单独成组。
 *
 * 已删除「默认连接的设备」：本应用只控制本机内置风扇，不存在可选的连接目标。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: CoolerViewModel,
    settings: AppSettings,
    onAbout: () -> Unit,
    onRecheck: () -> Unit,
) {
    val strings = LocalStrings.current
    val dynamicColor = settings.dynamicColor
    val darkMode = settings.darkMode
    val appLanguage = settings.appLanguage
    val palette = settings.palette
    val predictiveBack = settings.predictiveBack
    val contentScrollState = rememberScrollState()
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // (存储值, 展示文案)；MainActivity 按同一套 "system"/"light"/"dark" 解析
    val themeOptions = listOf(
        "system" to strings.settingsFollowSystem,
        "light" to strings.settingsDarkModeLight,
        "dark" to strings.settingsDarkModeDark,
    )
    // 语言选项用自称(endonym)：不管当前界面是什么语言，用户都能认出自己的语言，
    // 所以 "中文"/"English" 故意不进双语表。
    val languageOptions = listOf(
        "system" to strings.settingsFollowSystem,
        "zh" to "中文",
        "en" to "English",
    )
    // 存储里出现意外值时按「跟随系统」呈现，避免下拉行显示空白
    val themeMode = if (themeOptions.any { it.first == darkMode }) darkMode else "system"
    val language = if (languageOptions.any { it.first == appLanguage }) appLanguage else "system"

    PageScaffold(title = strings.settingsTitle) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .verticalScroll(contentScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SegmentedGroup {
                item(key = "dynamicColor") {
                    SegmentedSwitchRow(
                        title = strings.settingsDynamicColor,
                        summary = if (supportsDynamicColor) {
                            strings.settingsDynamicColorDesc
                        } else if (strings.langIsZh) {
                            "需要 Android 12 或更高版本"
                        } else {
                            "Requires Android 12 or later"
                        },
                        checked = supportsDynamicColor && dynamicColor,
                        enabled = supportsDynamicColor,
                        onCheckedChange = { if (supportsDynamicColor) vm.setDynamicColor(it) },
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_invert_colors_rounded_filled) },
                    )
                }
                item(key = "palette") {
                    SegmentedDropdownRow(
                        title = strings.settingsPalette,
                        options = PaletteStyles.all,
                        selected = palette,
                        onSelect = vm::setPalette,
                        optionLabel = { value -> when (value) {
                            PaletteStyles.NEUTRAL -> strings.settingsPaletteNeutral
                            PaletteStyles.VIBRANT -> strings.settingsPaletteVibrant
                            PaletteStyles.EXPRESSIVE -> strings.settingsPaletteExpressive
                            PaletteStyles.RAINBOW -> strings.settingsPaletteRainbow
                            PaletteStyles.FRUIT_SALAD -> strings.settingsPaletteFruitSalad
                            PaletteStyles.MONOCHROME -> strings.settingsPaletteMonochrome
                            PaletteStyles.FIDELITY -> strings.settingsPaletteFidelity
                            PaletteStyles.CONTENT -> strings.settingsPaletteContent
                            else -> strings.settingsPaletteTonalSpot
                        } },
                        leadingContent = { RowIcon(R.drawable.ms_style_fill1_24) },
                    )
                }
                item(key = "themeMode") {
                    SegmentedDropdownRow(
                        title = strings.settingsThemeMode,
                        options = themeOptions.map { it.first },
                        selected = themeMode,
                        onSelect = { vm.setDarkMode(it) },
                        optionLabel = { value ->
                            themeOptions.firstOrNull { it.first == value }?.second ?: value
                        },
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_dark_mode_rounded_filled) },
                    )
                }
                item(key = "language") {
                    SegmentedDropdownRow(
                        title = strings.settingsLanguage,
                        options = languageOptions.map { it.first },
                        selected = language,
                        onSelect = { vm.setAppLanguage(it) },
                        optionLabel = { value ->
                            languageOptions.firstOrNull { it.first == value }?.second ?: value
                        },
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_language_rounded_filled) },
                    )
                }
            }

            SegmentedGroup {
                item(key = "predictiveBack") {
                    SegmentedSwitchRow(
                        title = strings.settingsPredictiveBack,
                        summary = strings.settingsPredictiveBackDesc,
                        checked = predictiveBack,
                        onCheckedChange = vm::setPredictiveBack,
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_swipe_rounded_filled) },
                    )
                }
                item(key = "screenOffStop") {
                    SegmentedSwitchRow(
                        title = strings.settingsScreenOffStop,
                        summary = strings.settingsScreenOffStopDesc,
                        checked = settings.screenOffStopFan,
                        onCheckedChange = vm::setScreenOffStopFan,
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_bolt_rounded_filled) },
                    )
                }
                item(key = "temperatureSource") {
                    SegmentedDropdownRow(
                        title = strings.settingsTemperatureSource,
                        // 下标即 [FanNodes.temperatureSources] 的位置：名称表与传感器目录
                        // 的顺序对齐由 TemperatureSourceNamesTest 钉住。
                        options = strings.temperatureSourceNames.indices.toList(),
                        selected = settings.temperatureSource,
                        onSelect = vm::setTemperatureSource,
                        optionLabel = { index -> strings.temperatureSourceNames.getOrElse(index) { "" } },
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_speed_rounded_filled) },
                    )
                }
            }

            SegmentedGroup {
                item(key = "pollInterval") {
                    SegmentedNumberRow(
                        title = strings.settingsPollInterval,
                        summary = "${strings.settingsPollIntervalDesc} · ${strings.settingsRangeHint} ${FanTuning.MIN_POLL_INTERVAL_MS}–${FanTuning.MAX_POLL_INTERVAL_MS} ms",
                        value = settings.pollIntervalMs,
                        valueRange = FanTuning.POLL_INTERVAL_RANGE,
                        onValueChange = vm::setPollInterval,
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_schedule_rounded_filled) },
                    )
                }
                item(key = "fanTransition") {
                    SegmentedNumberRow(
                        title = strings.settingsFanTransition,
                        summary = "${strings.settingsFanTransitionDesc} · ${strings.settingsRangeHint} ${FanTuning.MIN_TRANSITION_MS}–${FanTuning.MAX_TRANSITION_MS} ms",
                        value = settings.fanTransitionMs,
                        valueRange = FanTuning.TRANSITION_RANGE,
                        onValueChange = vm::setFanTransitionMs,
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_stairs_rounded_filled) },
                    )
                }
            }

            SegmentedGroup {
                item(key = "recheck") {
                    SegmentedRow(
                        title = strings.settingsRecheck,
                        summary = strings.settingsRecheckDesc,
                        leadingContent = { RowIcon(R.drawable.ic_fan_five_blade) },
                        trailingContent = { RowIcon(R.drawable.materialsymbols_ic_chevron_right_rounded_filled) },
                        onClick = onRecheck,
                    )
                }
            }

            SegmentedGroup {
                item(key = "about") {
                    SegmentedRow(
                        title = strings.settingsAbout,
                        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_info_rounded_filled) },
                        trailingContent = { RowIcon(R.drawable.materialsymbols_ic_chevron_right_rounded_filled) },
                        onClick = onAbout,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

}
