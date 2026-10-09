package io.github.wxmyyds.coldfront.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.data.AppSettings
import io.github.wxmyyds.coldfront.fan.CurvePoint
import io.github.wxmyyds.coldfront.fan.CurvePreset
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.ui.component.PageScaffold
import io.github.wxmyyds.coldfront.ui.component.RowIcon
import io.github.wxmyyds.coldfront.ui.component.SegmentedGroup
import io.github.wxmyyds.coldfront.ui.component.SegmentedRow
import io.github.wxmyyds.coldfront.ui.i18n.AppStrings
import io.github.wxmyyds.coldfront.ui.i18n.LocalStrings
import kotlin.math.hypot
import kotlin.math.roundToInt

private val CHART_HEIGHT = 260.dp
private val CHART_PAD_LEFT = 34.dp
private val CHART_PAD_RIGHT = 12.dp
private val CHART_PAD_TOP = 12.dp
private val CHART_PAD_BOTTOM = 22.dp

/**
 * 温控曲线编辑页（二级页）。
 *
 * ## 编辑态与生效态分离
 * 本页**只写持久化**，不碰任何硬件节点；FanManager 订阅设置后自行生效。
 * 所以拖动曲线时风扇不会跟着乱跳，而"保存"是一个明确的、用户可见的动作。
 *
 * ## 三层提示里的第一、二层在这里
 * - 第 1 层：图上画出**默认曲线虚线**作参考基线，用户曲线低于它的区段一目了然；
 * - 第 2 层：保存时若命中危险判定，弹一次**说清后果**的确认（不是一闪而过的 Toast）。
 * 第 3 层（运行时首页持续提示）在 HomeScreen 上。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CurveScreen(vm: CoolerViewModel, settings: AppSettings, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val liveState by vm.liveState.collectAsStateWithLifecycle()
    val contentScrollState = rememberScrollState()

    var editor by remember { mutableStateOf(CurveEditorState.of(settings.smartCurve)) }
    var selectedPoint by remember { mutableStateOf(0) }
    var confirmDanger by remember { mutableStateOf(false) }

    // 磁盘上的曲线变了（例如从别处修复/恢复），且用户没有未保存的修改时，跟随刷新。
    LaunchedEffect(settings.smartCurve) {
        if (!editor.isDirty) editor = CurveEditorState.of(settings.smartCurve)
    }

    fun save() {
        vm.setSmartCurve(editor.curve)
        confirmDanger = false
    }

    PageScaffold(
        title = strings.curveTitle,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ms_arrow_back_fill1_24),
                    contentDescription = strings.back,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(contentScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                strings.curveDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.smartCurveCorrupt) {
                NoticeCard(
                    title = strings.curveCorruptTitle,
                    hint = strings.curveCorruptHint,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    actionLabel = strings.curveCorruptRepair,
                    onAction = { vm.repairSmartCurve() },
                )
            }

            // ── 实时运行点：用户第一次能直观看到"我这条线现在让风扇转多少"
            LivePointCard(
                strings = strings,
                temperatureC = liveState.temperatureC,
                targetDuty = liveState.temperatureC?.let { editor.curve.pwmFor(it.roundToInt()) },
                actualRpm = liveState.fanRpm,
                runtimeDanger = liveState.temperatureC?.let { editor.isDangerousAt(it.roundToInt()) } == true,
            )

            CurveChart(
                editor = editor,
                currentTempC = liveState.temperatureC,
                selectedPoint = selectedPoint,
                onSelect = { selectedPoint = it },
                onMove = { index, temp, duty -> editor = editor.movePoint(index, temp, duty) },
            )

            PointChips(
                editor = editor,
                strings = strings,
                selectedPoint = selectedPoint,
                onSelect = { selectedPoint = it },
            )

            PointDetail(
                editor = editor,
                strings = strings,
                index = selectedPoint,
                onMove = { temp, duty -> editor = editor.movePoint(selectedPoint, temp, duty) },
            )

            editor.dangerousFrom?.let { from ->
                val reference = FanCurve.BASELINE.pwmFor(from)
                NoticeCard(
                    title = strings.curveDangerTitle,
                    hint = strings.curveDangerHintFormat.format(from, editor.curve.pwmFor(from), reference),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            // ── 预设
            SegmentedGroup {
                CurvePreset.entries.forEach { preset ->
                    val active = editor.preset == preset
                    item(key = preset.name) {
                        SegmentedRow(
                            title = presetLabel(preset, strings),
                            summary = if (active) strings.curveNow else null,
                            leadingContent = { RowIcon(R.drawable.materialsymbols_ic_auto_mode_rounded_filled) },
                            trailingContent = if (active) {
                                { Icon(painterResource(R.drawable.ms_check_fill1_24), contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = { editor = editor.selectPreset(preset) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (editor.dangerousFrom != null) confirmDanger = true else save()
                    },
                    shapes = ButtonDefaults.shapes(),
                    enabled = editor.isDirty,
                    modifier = Modifier.weight(1f),
                ) { Text(strings.curveSave) }
                OutlinedButton(
                    onClick = { editor = editor.revert() },
                    shapes = ButtonDefaults.shapes(),
                    enabled = editor.isDirty,
                    modifier = Modifier.weight(1f),
                ) { Text(strings.curveRevert) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDanger) {
        AlertDialog(
            onDismissRequest = { confirmDanger = false },
            title = { Text(strings.curveDangerConfirmTitle) },
            text = { Text(strings.curveDangerConfirmBody) },
            confirmButton = {
                TextButton(onClick = { save() }) { Text(strings.curveDangerConfirmSave) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDanger = false }) { Text(strings.curveDangerConfirmBack) }
            },
        )
    }
}

private fun presetLabel(preset: CurvePreset, strings: AppStrings): String = when (preset) {
    CurvePreset.QUIET -> strings.curvePresetQuiet
    CurvePreset.BALANCED -> strings.curvePresetBalanced
    CurvePreset.PERFORMANCE -> strings.curvePresetPerformance
}

// ───────────────────── 实时运行点 ─────────────────────

@Composable
private fun LivePointCard(
    strings: AppStrings,
    temperatureC: Float?,
    targetDuty: Int?,
    actualRpm: Int?,
    runtimeDanger: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (runtimeDanger) scheme.errorContainer else scheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val content = if (runtimeDanger) scheme.onErrorContainer else scheme.onTertiaryContainer
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.curveNow, style = MaterialTheme.typography.labelMedium, color = content)
            Text(
                when {
                    temperatureC == null -> "--"
                    targetDuty == null -> "--"
                    else -> strings.curveLivePointFormat.format(temperatureC, targetDuty)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = content,
            )
            actualRpm?.let {
                Text("$it RPM", style = MaterialTheme.typography.labelMedium, color = content)
            }
            if (runtimeDanger) {
                // 第 3 层提示的"就地"版本：温度正落在危险区段时，编辑页也直说。
                Text(strings.curveDangerTitle, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

// ───────────────────── 图表 ─────────────────────

/** 温度 → 画布 X。轴固定在 [FanCurve.MIN_TEMP_C]..[FanCurve.MAX_TEMP_C]，不随曲线缩放。 */
private fun tempToX(tempC: Int, width: Float, padLeft: Float, padRight: Float): Float {
    val span = (FanCurve.MAX_TEMP_C - FanCurve.MIN_TEMP_C).toFloat()
    return padLeft + (tempC - FanCurve.MIN_TEMP_C) / span * (width - padLeft - padRight)
}

/** 占空比 → 画布 Y（0% 在底、99% 在顶）。 */
private fun dutyToY(duty: Int, height: Float, padTop: Float, padBottom: Float): Float {
    val usable = height - padTop - padBottom
    return padTop + usable - duty.toFloat() / FanCurve.MAX_DUTY * usable
}

private fun xToTemp(x: Float, width: Float, padLeft: Float, padRight: Float): Int {
    val span = width - padLeft - padRight
    if (span <= 0f) return FanCurve.MIN_TEMP_C
    val ratio = ((x - padLeft) / span).coerceIn(0f, 1f)
    return (FanCurve.MIN_TEMP_C + ratio * (FanCurve.MAX_TEMP_C - FanCurve.MIN_TEMP_C)).roundToInt()
}

private fun yToDuty(y: Float, height: Float, padTop: Float, padBottom: Float): Int {
    val usable = height - padTop - padBottom
    if (usable <= 0f) return FanCurve.MIN_DUTY
    val ratio = ((padTop + usable - y) / usable).coerceIn(0f, 1f)
    return (ratio * FanCurve.MAX_DUTY).roundToInt()
}

@Composable
private fun CurveChart(
    editor: CurveEditorState,
    currentTempC: Float?,
    selectedPoint: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, tempC: Int, duty: Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var draggedIndex by remember { mutableStateOf(-1) }

    val padLeft = with(density) { CHART_PAD_LEFT.toPx() }
    val padRight = with(density) { CHART_PAD_RIGHT.toPx() }
    val padTop = with(density) { CHART_PAD_TOP.toPx() }
    val padBottom = with(density) { CHART_PAD_BOTTOM.toPx() }

    val gridColor = scheme.outlineVariant
    val axisColor = scheme.onSurfaceVariant
    val curveColor = scheme.primary
    val dangerColor = scheme.error
    val baselineColor = scheme.outline
    val labelStyle = TextStyle(fontSize = 9.sp, color = axisColor)
    val touchSlop = with(density) { 32.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .onSizeChanged { size = it }
            // 手势必须吃掉竖向拖动，否则会和页面的 verticalScroll 抢事件。
            .pointerInput(editor.draft) {
                detectDragGestures(
                    onDragStart = { start ->
                        val index = nearestPoint(editor, start, size, padLeft, padRight, padTop, padBottom, touchSlop)
                        draggedIndex = index
                        if (index >= 0) onSelect(index)
                    },
                    onDragEnd = { draggedIndex = -1 },
                    onDragCancel = { draggedIndex = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        val index = draggedIndex
                        if (index >= 0 && size.width > 0) {
                            onMove(
                                index,
                                xToTemp(change.position.x, size.width.toFloat(), padLeft, padRight),
                                yToDuty(change.position.y, size.height.toFloat(), padTop, padBottom),
                            )
                        }
                    },
                )
            },
    ) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (w <= 0f || h <= 0f) return@Canvas

        // 网格
        listOf(0, 20, 40, 60, 80, FanCurve.MAX_DUTY).forEach { duty ->
            val y = dutyToY(duty, h, padTop, padBottom)
            drawLine(gridColor, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
            drawText(
                measurer,
                "$duty",
                topLeft = Offset(0f, y - 5f),
                style = labelStyle,
            )
        }
        (FanCurve.MIN_TEMP_C..FanCurve.MAX_TEMP_C step 20).forEach { temp ->
            val x = tempToX(temp, w, padLeft, padRight)
            drawLine(gridColor, Offset(x, padTop), Offset(x, h - padBottom), strokeWidth = 1f)
            drawText(
                measurer,
                "$temp",
                topLeft = Offset(x - 6f, h - padBottom + 3f),
                style = labelStyle,
            )
        }

        // 危险区：≥ 软阈值的那一段温度区间整体标出。**只提示不强制**，
        // 所以这里只是把事实画出来，不修改用户的曲线。
        val dangerX = tempToX(FanCurve.DANGER_TEMP_SOFT, w, padLeft, padRight)
        drawRect(
            color = dangerColor.copy(alpha = 0.07f),
            topLeft = Offset(dangerX, padTop),
            size = androidx.compose.ui.geometry.Size(w - padRight - dangerX, h - padTop - padBottom),
        )

        // 参考基线（默认曲线）：虚线，用户一眼看出自己压低了多少
        val baselinePath = androidx.compose.ui.graphics.Path()
        FanCurve.BASELINE.points.forEachIndexed { index, point ->
            val x = tempToX(point.tempC, w, padLeft, padRight)
            val y = dutyToY(point.pwmPercent, h, padTop, padBottom)
            if (index == 0) baselinePath.moveTo(x, y) else baselinePath.lineTo(x, y)
        }
        drawPath(
            baselinePath,
            baselineColor,
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        )

        // 用户曲线：整段画完，再单独把危险段覆盖成错误色
        val curvePath = androidx.compose.ui.graphics.Path()
        editor.draft.forEachIndexed { index, point ->
            val x = tempToX(point.tempC, w, padLeft, padRight)
            val y = dutyToY(point.pwmPercent, h, padTop, padBottom)
            if (index == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        drawPath(curvePath, curveColor, style = Stroke(width = 4f))

        // 实时游标：当前温度 + 曲线给出的目标转速
        currentTempC?.let { temperature ->
            val temp = temperature.roundToInt().coerceIn(FanCurve.MIN_TEMP_C, FanCurve.MAX_TEMP_C)
            val x = tempToX(temp, w, padLeft, padRight)
            drawLine(
                axisColor.copy(alpha = 0.5f),
                Offset(x, padTop),
                Offset(x, h - padBottom),
                strokeWidth = 2f,
            )
            val targetY = dutyToY(editor.curve.pwmFor(temp), h, padTop, padBottom)
            drawCircle(scheme.tertiary, radius = 7f, center = Offset(x, targetY))
        }

        // 断点
        editor.draft.forEachIndexed { index, point ->
            val center = Offset(
                tempToX(point.tempC, w, padLeft, padRight),
                dutyToY(point.pwmPercent, h, padTop, padBottom),
            )
            val radius = if (index == selectedPoint) 10f else 7f
            drawCircle(scheme.surface, radius = radius, center = center)
            drawCircle(
                if (index == selectedPoint) dangerColor else curveColor,
                radius = radius,
                center = center,
                style = Stroke(width = 3f),
            )
        }
    }
}

/** 距离触点最近的断点；超出容差返回 -1（避免误拖到远处的点）。 */
private fun nearestPoint(
    editor: CurveEditorState,
    touch: Offset,
    size: IntSize,
    padLeft: Float,
    padRight: Float,
    padTop: Float,
    padBottom: Float,
    slop: Float,
): Int {
    if (size.width <= 0 || size.height <= 0) return -1
    var best = -1
    var bestDistance = slop
    editor.draft.forEachIndexed { index, point ->
        val dx = touch.x - tempToX(point.tempC, size.width.toFloat(), padLeft, padRight)
        val dy = touch.y - dutyToY(point.pwmPercent, size.height.toFloat(), padTop, padBottom)
        val distance = hypot(dx, dy)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

// ───────────────────── 点位选择与精调 ─────────────────────

@Composable
private fun PointChips(
    editor: CurveEditorState,
    strings: AppStrings,
    selectedPoint: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        editor.draft.forEachIndexed { index, _ ->
            val active = index == selectedPoint
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                onClick = { onSelect(index) },
            ) {
                Text(
                    strings.curvePointFormat.format(index + 1),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 选中断点的精调。
 *
 * 两个滑条的取值范围直接取自 [CurveEditorState.tempBounds]/[CurveEditorState.dutyBounds]，
 * 所以用户**拖不出**非法位置；没有余量时（邻居把它夹死了）滑条置灰 —— 那正是事实。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PointDetail(
    editor: CurveEditorState,
    strings: AppStrings,
    index: Int,
    onMove: (tempC: Int, duty: Int) -> Unit,
) {
    val point: CurvePoint = editor.draft.getOrNull(index) ?: return
    val tempBounds = editor.tempBounds(index)
    val dutyBounds = editor.dutyBounds(index)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                strings.curvePointFormat.format(index + 1),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                "${strings.curveTempAxis}：${point.tempC}",
                style = MaterialTheme.typography.labelMedium,
            )
            val tempSlider = rememberSliderState(
                value = point.tempC.toFloat(),
                trackRange = tempBounds.first.toFloat()..tempBounds.last.toFloat().coerceAtLeast(tempBounds.first + 1f),
            )
            LaunchedEffect(point.tempC, tempBounds) { tempSlider.value = point.tempC.toFloat() }
            Slider(
                state = tempSlider,
                enabled = tempBounds.first < tempBounds.last,
                onValueChange = { onMove(it.roundToInt(), point.pwmPercent) },
            )

            Text(
                "${strings.curveDutyAxis}：${point.pwmPercent}",
                style = MaterialTheme.typography.labelMedium,
            )
            val dutySlider = rememberSliderState(
                value = point.pwmPercent.toFloat(),
                trackRange = dutyBounds.first.toFloat()..dutyBounds.last.toFloat().coerceAtLeast(dutyBounds.first + 1f),
            )
            LaunchedEffect(point.pwmPercent, dutyBounds) { dutySlider.value = point.pwmPercent.toFloat() }
            Slider(
                state = dutySlider,
                enabled = dutyBounds.first < dutyBounds.last,
                onValueChange = { onMove(point.tempC, it.roundToInt()) },
            )
        }
    }
}

// ───────────────────── 提示卡 ─────────────────────

@Composable
private fun NoticeCard(
    title: String,
    hint: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(actionLabel) }
            }
        }
    }
}
