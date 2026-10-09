package io.github.wxmyyds.coldfront.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import io.github.wxmyyds.coldfront.data.AppSettings
import io.github.wxmyyds.coldfront.ui.component.topLevelDragReversed
import io.github.wxmyyds.coldfront.ui.component.topLevelPositionAfterDrag
import io.github.wxmyyds.coldfront.ui.component.topLevelTargetAfterDrag
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Tab selection and drag settling share this duration; detail motion belongs to miuix-nav.
private const val TOP_LEVEL_PAGE_DURATION_MS = 300

/**
 * 只保留两个顶层页：风扇（控制 + 状态）与设置。
 * 设备列表 / 蓝牙扫描 / RGB 灯效页已删除 —— 本应用只控制 K90 至尊版内置风扇。
 */
@Composable
internal fun TopLevelPager(
    vm: CoolerViewModel,
    settings: AppSettings,
    isActive: Boolean,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenRecheck: () -> Unit,
    onOpenCurve: () -> Unit,
) {
    PrimaryPageStrip(
        pageCount = 2,
        selectedPage = selectedPage,
        isActive = isActive,
        onSelectPage = onSelectPage,
    ) { index ->
        when (index) {
            0 -> HomeScreen(vm, onOpenCurve)
            else -> SettingsScreen(vm, settings, onAbout = onOpenAbout, onRecheck = onOpenRecheck)
        }
    }
}

/**
 * Animate only tab selection. On a detail preview the selected page is already at offset zero,
 * even if the detail was opened before a tab animation finished. NavHost owns that whole return;
 * the strip must neither continue its own motion nor replay it when MAIN is recomposed.
 */
@Composable
internal fun PrimaryPageStrip(
    pageCount: Int,
    selectedPage: Int,
    isActive: Boolean,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val pagePosition = remember { Animatable(selectedPage.toFloat()) }
    val pageScope = rememberCoroutineScope()
    LaunchedEffect(selectedPage, isActive) {
        if (isActive) {
            pagePosition.animateTo(
                selectedPage.toFloat(),
                tween(TOP_LEVEL_PAGE_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } else {
            pagePosition.snapTo(selectedPage.toFloat())
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val pageWidth = maxWidth
        val pageWidthPx = with(LocalDensity.current) { pageWidth.toPx() }
        Box(
            Modifier.fillMaxSize().draggable(
                enabled = isActive,
                orientation = Orientation.Horizontal,
                reverseDirection = topLevelDragReversed(LocalLayoutDirection.current),
                state = rememberDraggableState { delta ->
                    pageScope.launch {
                        pagePosition.snapTo(
                            topLevelPositionAfterDrag(
                                pagePosition.value, delta, pageWidthPx, pageCount - 1,
                            ),
                        )
                    }
                },
                onDragStopped = { velocity ->
                    val target = topLevelTargetAfterDrag(pagePosition.value, velocity, pageCount - 1)
                    if (target != selectedPage) {
                        onSelectPage(target)
                    } else {
                        // The selected state did not change, so LaunchedEffect will not restart.
                        pageScope.launch {
                            pagePosition.animateTo(
                                target.toFloat(),
                                tween(TOP_LEVEL_PAGE_DURATION_MS, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                },
            ),
        ) {
            repeat(pageCount) { index ->
                Box(
                    Modifier.width(pageWidth).fillMaxHeight().offset {
                        // Use the settled position immediately, not one frame after the effect.
                        val position = if (isActive) pagePosition.value else selectedPage.toFloat()
                        IntOffset(((index - position) * pageWidthPx).roundToInt(), 0)
                    },
                ) { content(index) }
            }
        }
    }
}
