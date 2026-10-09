package io.github.wxmyyds.coldfront.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.data.AppSettings
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import io.github.wxmyyds.coldfront.ui.i18n.LocalStrings
import io.github.wxmyyds.coldfront.ui.i18n.rememberStrings
import io.github.wxmyyds.coldfront.ui.theme.BrandSeed
import io.github.wxmyyds.coldfront.ui.theme.RedmagicCoolerTheme
import io.github.wxmyyds.coldfront.ui.theme.ThemeSeed
import io.github.wxmyyds.coldfront.ui.theme.colorSchemeFromSeed
import io.github.wxmyyds.coldfront.ui.theme.pageLayerScheme

class MainActivity : ComponentActivity() {

    private val vm: CoolerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关掉系统对导航栏的强制对比度遮罩，edge-to-edge 下保持纯净透明。
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            AppContent(vm)
        }
    }

    @Composable
    private fun AppContent(vm: CoolerViewModel) {
        // One collected snapshot owns both readiness and every visible setting. Do not
        // split these into StateFlows: their independent scheduling can mix disk revisions.
        val snapshot by vm.settings.collectAsState()
        val settings = snapshot
        // Until the first read succeeds, only error messages use the system language.
        val strings = rememberStrings(override = settings?.appLanguage)
        val latestStrings by rememberUpdatedState(strings)
        val context = LocalContext.current
        // Install before the loading gate, so a failed first read can actually be reported.
        LaunchedEffect(vm) {
            vm.errors.collect {
                Toast.makeText(context, latestStrings.storageOperationFailed, Toast.LENGTH_LONG).show()
            }
        }
        if (settings == null) {
            StartupPlaceholder()
            return
        }
        val dark = when (settings.darkMode) {
            "light" -> false
            "dark" -> true
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        CompositionLocalProvider(
            LocalStrings provides strings,
        ) {
            RedmagicCoolerTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                palette = settings.palette,
            ) {
                SystemBarAppearance(dark)
                PermissionEffects()
                // 首次启动先走四步引导。引导只做只读检测，完成后写入偏好再进主页；
                // 设置页里的「运行检查」复用同一个页面（isRecheck = true）。
                if (settings.onboardingCompleted) {
                    AppNav(vm, settings)
                } else {
                    OnboardingScreen(vm = vm, isRecheck = false, onFinish = {})
                }
            }
        }
    }

    /**
     * 冷启动占位层：设置还没从 DataStore 读完时不渲染真实界面，只铺一层底色。
     *
     * 底色刻意用与动态取色最终态相同的种子与算法（而非品牌色），因为种子是同步可读的框架
     * 资源，不依赖 DataStore——真正需要异步读盘的只有 dynamicColor 开关本身。若占位层用品牌色，
     * 动态取色用户会先看到一帧品牌紫再跳到壁纸色，正是这里要消除的闪烁。
     */
    @Composable
    private fun StartupPlaceholder() {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val seed = if (ThemeSeed.supportsDynamic()) {
            androidx.compose.ui.res.colorResource(ThemeSeed.dynamicResourceId())
        } else {
            BrandSeed
        }
        val scheme = pageLayerScheme(colorSchemeFromSeed(seed, dark))
        Box(Modifier.fillMaxSize().background(scheme.background))
    }

    @Composable
    private fun SystemBarAppearance(dark: Boolean) {
        DisposableEffect(dark) {
            // WindowCompat returns WindowInsetsControllerCompat. Keep bars transparent for
            // edge-to-edge content, changing only the icon contrast with the app theme.
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }

    @Composable
    private fun PermissionEffects() {
        // 只申请通知权限。上游这里还申请 BLE 扫描 + 定位权限——那是给红魔散热器的
        // 蓝牙扫描用的；本 fork 控制的是 K90 Ultra 的内置风扇，走 root sysfs，
        // 完全不碰蓝牙，向用户要这些权限只会造成困惑。
        val perms = remember {
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        }
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { }
        // 只在「还没授予」且本次界面尚未问过时申请：
        // 否则每次 Activity 重建（旋转、从设置返回）都会再弹一次系统对话框。
        val context = LocalContext.current
        var asked by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        if (perms.isNotEmpty() && !asked && !hasNotificationPermission(context)) {
            LaunchedEffect(Unit) {
                asked = true
                launcher.launch(perms)
            }
        }
    }
}

/**
 * Destinations owned by miuix-nav. [Main] hosts the two tabs; everything else is a detail page
 * drawn above it. Data objects keep `rememberSaveable` stable across process death.
 */
@Serializable
private sealed interface AppRoute : NavKey {
    @Serializable
    data object Main : AppRoute

    @Serializable
    data object About : AppRoute

    /** 设置里的「运行检查」——复用引导页，但不写完成状态。 */
    @Serializable
    data object Recheck : AppRoute

    /** 首页「智能温控」行进入的温控曲线编辑页。 */
    @Serializable
    data object Curve : AppRoute
}

/**
 * The two primary tabs, inside the top-level destination.
 *
 * [WindowInsets.navigationBars] is consumed here rather than by an outer layout: the bar that draws
 * over that inset is now a sibling of this content inside MAIN, so the two belong to one page and
 * must reserve the inset between them - the content pads away from it and the bar sits on top of it.
 */
@Composable
private fun MainPage(
    vm: CoolerViewModel,
    settings: AppSettings,
    isActive: Boolean,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenRecheck: () -> Unit,
    onOpenCurve: () -> Unit,
) {
    val contentInsets = WindowInsets.navigationBars
    // windowInsetsPadding, not padding(insets): WindowInsets is not a PaddingValues, and converting
    // it first would bake the values in at composition rather than per-layout pass.
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .consumeWindowInsets(contentInsets),
    ) {
        TopLevelPager(
            vm = vm,
            settings = settings,
            isActive = isActive,
            selectedPage = selectedPage,
            onSelectPage = onSelectPage,
            onOpenAbout = onOpenAbout,
            onOpenRecheck = onOpenRecheck,
            onOpenCurve = onOpenCurve,
        )
    }
}

/** Identifies the primary tabs inside the pager; these are not NavHost routes. */
private object TabRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/** 底部导航/Rail 的 tab 项：图标按选中态 composable 绘制，支持选中/未选中两套图标。 */
private data class TabItem(
    val route: String,
    val icon: @Composable (selected: Boolean) -> Unit,
    val label: @Composable () -> String,
)

@Composable
private fun AppNav(vm: CoolerViewModel, settings: AppSettings) {
    val nav = top.yukonga.miuix.kmp.nav.core.rememberNavController(AppRoute.Main)
    val strings = LocalStrings.current
    val predictiveBack = settings.predictiveBack

    val open = { route: AppRoute ->
        if (nav.backStack.lastOrNull() != route) nav.push(route)
    }

    val items: List<TabItem> = remember(strings) {
        listOf(
            TabItem(
                TabRoutes.HOME,
                {
                    Icon(
                        painterResource(if (it) R.drawable.materialsymbols_ic_home_rounded_filled else R.drawable.materialsymbols_ic_home_rounded),
                        contentDescription = null,
                    )
                },
            ) { strings.navHome },
            // 设置 tab 用 Rounded 齿轮：选中 Fill、未选中 outline，由 NavigationBar/Rail 的选中态驱动。
            TabItem(
                TabRoutes.SETTINGS,
                {
                    Icon(
                        painterResource(if (it) R.drawable.materialsymbols_ic_settings_rounded_filled else R.drawable.materialsymbols_ic_settings_rounded),
                        contentDescription = null,
                    )
                },
            ) { strings.navSettings },
        )
    }
    // Tabs identify pages inside the pager, so they are not navigation destinations.
    val tabRoutes = remember(items) { items.map { it.route } }
    val selectedPage = rememberSaveable { mutableIntStateOf(0) }

    // Switching tabs stays inside Main: no navigation, so no transition and no predictive back.
    val navigateToTab: (String) -> Unit = { route ->
        tabRoutes.indexOf(route)
            .takeIf { it >= 0 }
            ?.let { selectedPage.intValue = it }
    }

    // Use existing foundation/Material3 APIs, without adding a window-size dependency.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        val topLevelIsCurrent = nav.backStack.lastOrNull()?.let { it == AppRoute.Main } ?: true
        val cornerRadius = rememberScreenCornerRadius()
        val chrome = @Composable {
            if (!useRail) {
                NavigationBar(
                    modifier = Modifier.semantics { isTraversalGroup = true },
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars.union(
                        WindowInsets(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 12.dp),
                    ),
                ) {
                    items.forEach { item ->
                        val itemSelected = tabRoutes.getOrNull(selectedPage.intValue) == item.route
                        NavigationBarItem(
                            selected = itemSelected,
                            onClick = { navigateToTab(item.route) },
                            // Label + native selectable semantics announce the destination once.
                            icon = { item.icon(itemSelected) },
                            label = { Text(item.label()) },
                        )
                    }
                }
            } else {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .semantics { isTraversalGroup = true },
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    // Scroll only the rail contents: keep system insets and the sibling page fixed.
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items.forEach { item ->
                            val itemSelected = tabRoutes.getOrNull(selectedPage.intValue) == item.route
                            NavigationRailItem(
                                selected = itemSelected,
                                onClick = { navigateToTab(item.route) },
                                icon = { item.icon(itemSelected) },
                                label = { Text(item.label()) },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                NavDisplay(
                    navController = nav,
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()
                        .semantics { isTraversalGroup = true },
                    transition = NavTransitions.MiuixDefault,
                    effects = NavDisplayEffects(
                        cornerClipRadius = cornerRadius,
                        cornerClipMode = NavCornerClipMode.Leading,
                        dimAmount = 0.5f,
                    ),
                ) {
                    entry<AppRoute.Main> {
                        if (useRail) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    MainPage(
                                        vm = vm,
                                        settings = settings,
                                        isActive = topLevelIsCurrent,
                                        selectedPage = selectedPage.intValue,
                                        onSelectPage = { selectedPage.intValue = it },
                                        onOpenAbout = { open(AppRoute.About) },
                                        onOpenRecheck = { open(AppRoute.Recheck) },
                                        onOpenCurve = { open(AppRoute.Curve) },
                                    )
                                }
                                chrome()
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    MainPage(
                                        vm = vm,
                                        settings = settings,
                                        isActive = topLevelIsCurrent,
                                        selectedPage = selectedPage.intValue,
                                        onSelectPage = { selectedPage.intValue = it },
                                        onOpenAbout = { open(AppRoute.About) },
                                        onOpenRecheck = { open(AppRoute.Recheck) },
                                        onOpenCurve = { open(AppRoute.Curve) },
                                    )
                                }
                                chrome()
                            }
                        }
                    }
                    entry<AppRoute.About> {
                        if (!predictiveBack) BackHandler { nav.pop() }
                        AboutScreen(onBack = { nav.pop() })
                    }
                    entry<AppRoute.Recheck> {
                        if (!predictiveBack) BackHandler { nav.pop() }
                        OnboardingScreen(vm = vm, isRecheck = true, onFinish = { nav.pop() })
                    }
                    entry<AppRoute.Curve> {
                        if (!predictiveBack) BackHandler { nav.pop() }
                        CurveScreen(vm = vm, settings = settings, onBack = { nav.pop() })
                    }
                }
            }
        }
    }
}
