package io.github.wxmyyds.coldfront.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.fan.FanCheckId
import io.github.wxmyyds.coldfront.fan.FanCheckReport
import io.github.wxmyyds.coldfront.fan.FanCheckState
import io.github.wxmyyds.coldfront.ui.i18n.AppStrings
import io.github.wxmyyds.coldfront.ui.i18n.LocalStrings
import io.github.wxmyyds.coldfront.ui.component.PageScaffold
import io.github.wxmyyds.coldfront.ui.theme.EmphasizedTypography
import io.github.wxmyyds.coldfront.ui.theme.optionContainerColor

/**
 * 首次启动引导 / 「运行检查」页。
 *
 * 四步（与产品确认的参考图一致）：欢迎 → Root 授权 → 运行检查 → 完成。
 * 从设置进入时（[isRecheck]）跳过欢迎页，最后一步改为「完成」并返回。
 *
 * **这页只做只读检测**：所有探测都走 [CoolerViewModel.runChecks]（内部只用 `cat`/
 * `getprop`/`id -u`），不写 `pwm_duty`、不写 `target_level`、不改 `cooling_fan_enable`。
 * 真正的接管发生在用户回到首页、第一次操作风扇时。
 *
 * 必要条件（Root、风扇节点、温度、转速）失败时**不伪装成完成**：进入首页后调速控件禁用，
 * 并保留「运行检查」入口。通知权限是可选项。
 */
private enum class OnboardingStep { WELCOME, ROOT, CHECKS, DONE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: CoolerViewModel, isRecheck: Boolean, onFinish: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val report by vm.checks.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()

    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        // 重新跑一遍：通知项的结果会随之更新，其它项也顺带刷新。
        vm.runChecks(granted)
    }

    val steps = remember(isRecheck) {
        if (isRecheck) listOf(OnboardingStep.ROOT, OnboardingStep.CHECKS, OnboardingStep.DONE)
        else OnboardingStep.entries.toList()
    }
    var index by rememberSaveable(isRecheck) { mutableIntStateOf(0) }
    val step = steps.getOrElse(index) { steps.last() }

    // 进入 Root / 检查步骤时自动跑一次（只读）。重进页面不会重复触发。
    LaunchedEffect(step) {
        val needsReport = step == OnboardingStep.ROOT || step == OnboardingStep.CHECKS
        if (needsReport && report == null && !checking) vm.runChecks(notificationGranted)
    }

    val rootState = report?.stateOf(FanCheckId.ROOT)

    PageScaffold(title = strings.appName) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = steps.indexOf(targetState) >= steps.indexOf(initialState)
                    val enter = slideInHorizontally { if (forward) it else -it }
                    val exit = slideOutHorizontally { if (forward) -it else it }
                    (enter togetherWith exit)
                },
                label = "onboardingStep",
                modifier = Modifier.weight(1f),
            ) { target ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (target) {
                        OnboardingStep.WELCOME -> WelcomeContent(strings)
                        OnboardingStep.ROOT -> RootContent(strings, rootState, checking, onRetry = { vm.runChecks(notificationGranted) })
                        OnboardingStep.CHECKS -> ChecksContent(
                            strings = strings,
                            report = report,
                            checking = checking,
                            notificationGranted = notificationGranted,
                            onRetry = { vm.runChecks(notificationGranted) },
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notificationGranted = true
                                }
                            },
                        )
                        OnboardingStep.DONE -> DoneContent(strings, report, isRecheck)
                    }
                }
            }

            StepDots(count = steps.size, current = index)
            Spacer(Modifier.height(12.dp))
            NavButtons(
                isFirst = index == 0,
                primaryLabel = when {
                    step == OnboardingStep.DONE && isRecheck -> strings.doneClose
                    // 必要条件没过时不写「开始使用」：按钮必须如实说明只是「仍然进入」，
                    // 否则用户会以为风扇已经可用（实际首页会禁用全部控制）。
                    step == OnboardingStep.DONE && report?.requiredPassed != true -> strings.doneEnterAnyway
                    step == OnboardingStep.DONE -> strings.doneEnter
                    else -> strings.onboardingNext
                },
                onPrev = { if (index > 0) index-- },
                onPrimary = {
                    if (step == OnboardingStep.DONE) {
                        if (!isRecheck) {
                            // 只在真正完成引导时落盘；同时发起只读挂载（不写节点）。
                            vm.completeOnboarding()
                            vm.activateControl()
                        }
                        onFinish()
                    } else {
                        index = (index + 1).coerceAtMost(steps.lastIndex)
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────── 各步骤内容 ─────────────────────

@Composable
private fun WelcomeContent(strings: AppStrings) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.padding(20.dp),
        )
    }
    Spacer(Modifier.height(28.dp))
    Text(strings.welcomeTitle, style = EmphasizedTypography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        strings.welcomeSubtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RootContent(
    strings: AppStrings,
    rootState: FanCheckState?,
    checking: Boolean,
    onRetry: () -> Unit,
) {
    val pending = checking || rootState == null
    StatusRing(
        mark = when {
            pending -> null
            rootState == FanCheckState.PASS -> Mark.PASS
            else -> Mark.FAIL
        },
    )
    Spacer(Modifier.height(28.dp))
    Text(strings.checkRoot, style = EmphasizedTypography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            pending -> strings.rootChecking
            rootState == FanCheckState.PASS -> strings.rootGranted
            else -> strings.rootDenied
        },
        style = MaterialTheme.typography.bodyLarge,
        color = when {
            pending -> MaterialTheme.colorScheme.onSurfaceVariant
            rootState == FanCheckState.PASS -> passColor()
            else -> MaterialTheme.colorScheme.error
        },
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        strings.rootHint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onRetry, shapes = ButtonDefaults.shapes(), enabled = !checking) {
        Text(strings.rootRetry)
    }
}

@Composable
private fun ChecksContent(
    strings: AppStrings,
    report: FanCheckReport?,
    checking: Boolean,
    notificationGranted: Boolean,
    onRetry: () -> Unit,
    onRequestNotification: () -> Unit,
) {
    if (report != null) {
        // 圆环只数**必需项**：通知权限是可选项，把它算进分母会让页面一直显示 5/6，
        // 看上去像有一个必须修好的失败项。
        CountRing(passed = report.requiredPassedCount, total = FanCheckReport.REQUIRED_CHECKS.size)
        Spacer(Modifier.height(20.dp))
    } else {
        CircularProgressIndicator(Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
    }
    Text(strings.checkTitle, style = EmphasizedTypography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(4.dp))
    Text(
        strings.checkSubtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    Surface(
        shape = MaterialTheme.shapes.large,
        color = optionContainerColor(MaterialTheme.colorScheme),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            CheckRows(report, checking)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (report != null) {
                    Text(
                        if (report.requiredPassed) strings.checkSummaryReady else strings.checkSummaryNotReady,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.requiredPassed) passColor() else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onRetry, enabled = !checking) { Text(strings.checkRetry) }
            }
        }
    }

    if (!notificationGranted) {
        Spacer(Modifier.height(12.dp))
        Text(
            strings.checkNotificationHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequestNotification, shapes = ButtonDefaults.shapes()) {
            Text(strings.checkNotificationGrant)
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        strings.checkRequiredHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DoneContent(strings: AppStrings, report: FanCheckReport?, isRecheck: Boolean) {
    val ready = report?.requiredPassed == true
    StatusRing(mark = if (ready) Mark.PASS else Mark.FAIL)
    Spacer(Modifier.height(28.dp))
    Text(
        if (isRecheck) strings.checkTitle else strings.doneTitle,
        style = EmphasizedTypography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (ready) strings.doneReady else strings.doneNotReady,
        style = MaterialTheme.typography.bodyLarge,
        color = if (ready) passColor() else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
}

// ───────────────────── 检查项列表 ─────────────────────

/**
 * 检查项列表。首页在「未就绪」时也复用这段，避免两处各写一套状态文案。
 */
@Composable
internal fun CheckRows(report: FanCheckReport?, checking: Boolean) {
    val strings = LocalStrings.current
    val ids = listOf(
        FanCheckId.MODEL,
        FanCheckId.ROOT,
        FanCheckId.FAN_NODE,
        FanCheckId.TEMPERATURE,
        FanCheckId.SPEED,
        FanCheckId.NOTIFICATION,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (id in ids) {
            val outcome = report?.outcome(id)
            val label = when (id) {
                FanCheckId.MODEL -> strings.checkModel
                FanCheckId.ROOT -> strings.checkRoot
                FanCheckId.FAN_NODE -> strings.checkNode
                FanCheckId.TEMPERATURE -> strings.checkTemperature
                FanCheckId.SPEED -> strings.checkSpeed
                FanCheckId.NOTIFICATION -> strings.checkNotification
            }
            // 通知是可选功能：未开启时用专门的「未开启（可选）」文案，
            // 而不是和必需项失败共用「异常」——否则用户会去修一个不必修的项。
            val stateText = if (id == FanCheckId.NOTIFICATION && outcome != null && outcome.state != FanCheckState.PASS) {
                strings.checkOptionalOff
            } else {
                when (outcome?.state) {
                    FanCheckState.PASS -> strings.checkPass
                    FanCheckState.FAIL -> strings.checkFail
                    FanCheckState.SKIP -> strings.checkSkip
                    null -> if (checking) strings.checkRunning else strings.checkSkip
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    val detail = outcome?.detail.orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stateText,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (outcome?.state) {
                        FanCheckState.PASS -> passColor()
                        FanCheckState.FAIL -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                StateMark(outcome?.state)
            }
        }
    }
}

private enum class Mark { PASS, FAIL, SKIP }

@Composable
private fun StateMark(state: FanCheckState?) {
    val (mark, container, content) = when (state) {
        FanCheckState.PASS -> Triple(Mark.PASS, passColor(), Color.White)
        FanCheckState.FAIL -> Triple(Mark.FAIL, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        else -> Triple(Mark.SKIP, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { MarkGlyph(mark, content) }
    }
}

/** 圆形状态标：对勾用官方 XML（图标库的 check 已被上游坐标 bug 破坏），失败/跳过用字形。 */
@Composable
private fun MarkGlyph(mark: Mark, tint: Color) {
    when (mark) {
        Mark.PASS -> Icon(
            painterResource(R.drawable.ms_check_fill1_24),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Mark.FAIL -> Text("!", color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        Mark.SKIP -> Text("–", color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusRing(mark: Mark?) {
    if (mark == null) {
        CircularProgressIndicator(Modifier.size(96.dp))
        return
    }
    val container = if (mark == Mark.PASS) passColor() else MaterialTheme.colorScheme.error
    Surface(shape = CircleShape, color = container.copy(alpha = 0.15f), modifier = Modifier.size(112.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Surface(shape = CircleShape, color = container, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    MarkGlyph(mark, Color.White)
                }
            }
        }
    }
}

@Composable
private fun CountRing(passed: Int, total: Int) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$passed/$total", style = EmphasizedTypography.headlineSmall)
    }
}

// ───────────────────── 底部指示与按钮 ─────────────────────

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { dot ->
            Surface(
                shape = CircleShape,
                color = if (dot == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (dot == current) Modifier.width(20.dp) else Modifier.size(8.dp))
                    .height(8.dp),
            ) {}
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavButtons(
    isFirst: Boolean,
    primaryLabel: String,
    onPrev: () -> Unit,
    onPrimary: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isFirst) {
            OutlinedButton(
                onClick = onPrev,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) { Text(strings.onboardingPrev) }
        }
        Button(
            onClick = onPrimary,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(if (isFirst) 1f else 1.4f),
        ) { Text(primaryLabel) }
    }
}

// ───────────────────── 小工具 ─────────────────────

/** 通过色：跟随主题的 tertiary 容器色，避免硬编码绿色。 */
@Composable
private fun passColor(): Color = MaterialTheme.colorScheme.tertiary

internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
