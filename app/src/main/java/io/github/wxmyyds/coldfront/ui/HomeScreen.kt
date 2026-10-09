package io.github.wxmyyds.coldfront.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.data.AppSettings
import io.github.wxmyyds.coldfront.domain.ConnectionState
import io.github.wxmyyds.coldfront.domain.CoolerLiveState
import io.github.wxmyyds.coldfront.fan.CurvePreset
import io.github.wxmyyds.coldfront.fan.FanCheckReport
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.ui.component.AppMotion
import io.github.wxmyyds.coldfront.ui.component.RowIcon
import io.github.wxmyyds.coldfront.ui.component.PageScaffold
import io.github.wxmyyds.coldfront.ui.component.SegmentedGroup
import io.github.wxmyyds.coldfront.ui.component.SegmentedRow
import io.github.wxmyyds.coldfront.ui.component.SegmentedSwitchRow
import io.github.wxmyyds.coldfront.ui.component.staticStandaloneRowShapes
import io.github.wxmyyds.coldfront.ui.i18n.AppStrings
import io.github.wxmyyds.coldfront.ui.i18n.LocalStrings
import io.github.wxmyyds.coldfront.ui.theme.EmphasizedTypography
import io.github.wxmyyds.coldfront.ui.theme.optionContainerColor
import kotlin.math.roundToInt

/**
 * 首页 —— 本机内置风扇的唯一控制界面。
 *
 * 与上游（红魔蓝牙散热器）的差异：
 * - 没有「连接设备/添加设备」：本机风扇永远在位，只有「已挂载」和「未挂载」两种状态；
 * - 没有蓝牙信号强度、没有功率读数（内置风扇不上报）、没有过冷保护与供电限档弹窗；
 * - 「散热开关」→「风扇开关」，「破坏神」→「满速模式」，措辞不再借用外置散热器语义。
 *
 * 未挂载时首页**不提供任何写入入口**，只有只读的「运行检查」和显式的「启用控制」；
 * 启用前不会修改系统风扇策略。
 */
private enum class HomeContentState { Connected, Attaching, NotReady }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: CoolerViewModel, onOpenCurve: () -> Unit) {
    val strings = LocalStrings.current
    val state by vm.liveState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val report by vm.checks.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val contentState = when {
        state.connection == ConnectionState.CONNECTED -> HomeContentState.Connected
        state.connection == ConnectionState.CONNECTING || state.connection == ConnectionState.DISCOVERING ->
            HomeContentState.Attaching
        else -> HomeContentState.NotReady
    }
    val contentScrollState = rememberScrollState()
    // transitionSpec 不是 composable 上下文：在这里取好 spec，再交给 lambda 使用。
    val motionScheme = MaterialTheme.motionScheme

    PageScaffold(title = strings.homeTitle) { inner ->
        AnimatedContent(
            targetState = contentState,
            transitionSpec = {
                AppMotion.contentChange(motionScheme)
            },
            label = "homeConnectionContent",
        ) { target ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(contentScrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (target) {
                    HomeContentState.Connected -> ConnectedContent(vm, state, settings, onOpenCurve)
                    HomeContentState.Attaching -> AttachingContent(strings)
                    HomeContentState.NotReady -> NotReadyContent(vm, state, report, checking)
                }
            }
        }
    }
}

// ───────────────────── 已挂载 ─────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedContent(
    vm: CoolerViewModel,
    state: CoolerLiveState,
    settings: AppSettings?,
    onOpenCurve: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val curve = settings?.smartCurve

    // 同一时刻只显示一条横幅，按"有多要紧"排序：
    // 控制权交出去了 > 写入失败 > 数据可能过期 > 息屏停转中。
    when {
        state.controlDegraded -> StatusBanner(
            title = strings.controlDegradedTitle,
            hint = strings.controlDegradedHint,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            actionLabel = strings.homeRecheck,
            onAction = { vm.runChecks(hasNotificationPermission(context)) },
        )
        state.controlError -> StatusBanner(
            title = strings.homeControlFailedTitle,
            hint = strings.homeControlFailedHint,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            actionLabel = strings.homeRecheck,
            onAction = { vm.runChecks(hasNotificationPermission(context)) },
        )
        state.telemetryDegraded -> StatusBanner(
            title = strings.telemetryDegradedTitle,
            hint = strings.telemetryDegradedHint,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            actionLabel = strings.homeRecheck,
            onAction = { vm.runChecks(hasNotificationPermission(context)) },
        )
        // 息屏停转是**自动化替用户做的决定**，必须说清风扇为什么停着 —— 那是正常行为，不是故障。
        state.screenOffStopped -> StatusBanner(
            title = strings.settingsScreenOffStop,
            hint = strings.screenOffStoppedNotice,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    TempHero(state)

    // 第 3 层提示：温度正落在"曲线给得太低"的区段时**持续**显示。
    // 用户平时看的是首页而不是编辑页 —— 只有这一层才真正起作用，
    // 编辑时弹过一次的确认早被忘了。
    val dangerousAt = state.temperatureC?.roundToInt()?.takeIf { temp ->
        curve != null && state.smartOn && FanCurve.isDangerous(temp, curve.pwmFor(temp))
    }
    if (dangerousAt != null && curve != null) {
        StatusBanner(
            title = strings.curveDangerTitle,
            hint = strings.curveDangerHintFormat.format(
                dangerousAt,
                curve.pwmFor(dangerousAt),
                FanCurve.BASELINE.pwmFor(dangerousAt),
            ),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    SegmentedSwitchRow(
        title = strings.homeFanSwitch,
        summary = strings.homeFanSwitchDesc,
        checked = state.coolingOn,
        enabled = state.isConnected,
        shapes = staticStandaloneRowShapes(),
        onCheckedChange = vm::setCooling,
        leadingContent = { RowIcon(R.drawable.materialsymbols_ic_ac_unit_rounded_filled) },
    )

    SegmentedGroup {
        item(key = "smart") {
            SegmentedSwitchRow(
                title = strings.homeSmart,
                summary = strings.homeSmartDesc,
                checked = state.smartOn,
                // 满速模式下禁用（与上游一致：两种自动策略互斥）
                enabled = state.isConnected && !state.boostOn,
                onCheckedChange = { vm.setSmart(it) },
                leadingContent = { RowIcon(R.drawable.materialsymbols_ic_auto_mode_rounded_filled) },
            )
        }
        item(key = "curve") {
            SegmentedRow(
                title = strings.curveTitle,
                summary = curveSummary(curve, strings),
                leadingContent = { RowIcon(R.drawable.materialsymbols_ic_speed_rounded_filled) },
                trailingContent = {
                    RowIcon(R.drawable.materialsymbols_ic_chevron_right_rounded_filled)
                },
                onClick = onOpenCurve,
            )
        }
        item(key = "boost") {
            SegmentedSwitchRow(
                title = strings.homeFullSpeed,
                summary = strings.homeFullSpeedDesc,
                checked = state.boostOn,
                enabled = state.isConnected && !state.smartOn,
                onCheckedChange = { vm.setBoost(it) },
                leadingContent = { RowIcon(R.drawable.materialsymbols_ic_bolt_rounded_filled) },
            )
        }
    }

    LevelSection(vm, state, strings)
}

/** 曲线行摘要：不用进编辑页也知道现在用的是哪条线。 */
private fun curveSummary(curve: FanCurve?, strings: AppStrings): String = when (curve?.matchingPreset()) {
    CurvePreset.QUIET -> strings.curvePresetQuiet
    CurvePreset.BALANCED -> strings.curvePresetBalanced
    CurvePreset.PERFORMANCE -> strings.curvePresetPerformance
    else -> strings.curvePresetCustom
}

/** 挂载中（探测 Root / 风扇节点）：只读过程，不写节点。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AttachingContent(strings: AppStrings) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(strings.homeConnecting, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * 未挂载：显示真实原因 + 只读检查结果，并提供「启用控制」。
 * 这里刻意不做任何自动接管 —— 用户不点「启用控制」就不会写风扇节点。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotReadyContent(
    vm: CoolerViewModel,
    state: CoolerLiveState,
    report: FanCheckReport?,
    checking: Boolean,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val failed = state.connection == ConnectionState.FAILED

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = optionContainerColor(MaterialTheme.colorScheme),
        ) {
            Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.materialsymbols_ic_ac_unit_rounded_filled),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            if (failed) strings.homeFailed else strings.homeNotActivated,
            style = EmphasizedTypography.headlineSmall,
        )
        Text(
            if (failed) strings.homeFailedHint else strings.homeNotActivatedHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (report != null || checking) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = optionContainerColor(MaterialTheme.colorScheme),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(8.dp)) {
                    CheckRows(report, checking)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.activateControl() },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(strings.homeActivate) }
        OutlinedButton(
            onClick = { vm.runChecks(hasNotificationPermission(context)) },
            shapes = ButtonDefaults.shapes(),
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) strings.checkRunning else strings.homeRecheck) }
        Text(
            strings.checkRequiredHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ───────────────────── 状态横幅 ─────────────────────

@Composable
private fun StatusBanner(
    title: String,
    hint: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium)
            // 纯告知类横幅（例如"息屏停转中"）没有可执行的动作，不该硬塞一个按钮。
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(actionLabel) }
            }
        }
    }
}

// ───────────────────── 温度英雄卡 ─────────────────────

/** 温度英雄卡：表面容器分层 + 强调排印 + 指标胶囊（只显示真实存在的读数）。 */
@Composable
private fun TempHero(state: CoolerLiveState) {
    val strings = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    // 运转中 → primaryContainer；停转 → 与选项容器同档，避免在页面里孤立成一块深色。
    val container by animateColorAsState(
        targetValue = if (state.coolingOn) scheme.primaryContainer else optionContainerColor(scheme),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>(),
        label = "heroContainer",
    )
    val onContainer = if (state.coolingOn) scheme.onPrimaryContainer else scheme.onSurface

    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CoolerArt(
                    state.deviceType,
                    modifier = Modifier.size(64.dp),
                    iconTint = onContainer,
                )
                Column {
                    Text(
                        state.deviceName.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = onContainer,
                    )
                    Text(
                        strings.homeConnected,
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    strings.homeTemp,
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                )
                Text(
                    state.temperatureText,
                    style = EmphasizedTypography.displayLarge,
                    color = onContainer,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 内置风扇只上报温度与转速：不再显示 RSSI（无蓝牙）和功率（不上报）。
                MetricPill(
                    R.drawable.materialsymbols_ic_speed_rounded_filled,
                    state.fanRpm?.toString() ?: "--",
                    "RPM",
                    onContainer,
                )
                MetricPill(
                    R.drawable.materialsymbols_ic_bolt_rounded_filled,
                    state.fanPercent.toString(),
                    "%",
                    onContainer,
                )
            }
        }
    }
}

/** 单个指标胶囊：图标 + 数值 + 单位同处一行同一容器。 */
@Composable
private fun MetricPill(
    @androidx.annotation.DrawableRes resId: Int,
    value: String,
    unit: String,
    content: Color,
) {
    // 胶囊底是对 content 的装饰性淡色叠加；文字与图标保持全强度对比度。
    Surface(shape = CircleShape, color = content.copy(alpha = 0.12f)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painterResource(resId),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
            Text(
                unit,
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

// ───────────────────── 手动调速 ─────────────────────

/** 风扇转速：强调数字 + 滑条（内置风扇 `pwm_duty` 0–99 无极调速）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LevelSection(vm: CoolerViewModel, state: CoolerLiveState, strings: AppStrings) {
    val displayedPercent = state.pendingFanPercent ?: state.fanPercent

    if (state.isConnected && state.smartOn) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.materialsymbols_ic_auto_mode_rounded_filled),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    strings.homeSmartActiveLevel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        return
    }
    if (!state.manualLevelEnabled) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 尺寸/展开类动画统一从主题取 spec，不在业务代码里硬编码 spring
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = optionContainerColor(MaterialTheme.colorScheme)),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.materialsymbols_ic_speed_rounded_filled), contentDescription = null)
                Text(strings.homeLevel, style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.homeLevelPercent.format(displayedPercent),
                    style = EmphasizedTypography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // MD3E：Slider 走 SliderState。拖动期间以滑条为准，松手后再由节点回读值同步。
            var dragging by remember { mutableStateOf(false) }
            val percent = displayedPercent.toFloat()
            val percentSlider = rememberSliderState(value = percent, trackRange = 0f..100f)
            androidx.compose.runtime.LaunchedEffect(percent, dragging) {
                if (!dragging) percentSlider.value = percent
            }
            Slider(
                state = percentSlider,
                modifier = Modifier.semantics {
                    contentDescription = strings.homeLevel
                    stateDescription = strings.homeLevelPercent.format(percentSlider.value.roundToInt())
                },
                onValueChange = {
                    dragging = true
                    percentSlider.value = it
                    vm.setFanSpeed(it.toInt())
                },
                onValueChangeFinished = { dragging = false },
                enabled = state.isConnected,
            )
        }
    }
}
