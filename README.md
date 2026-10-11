# ColdFront

REDMI K90 至尊版（K90 Ultra / K90 Max）**内置风扇**控制器 —— 基于 **Material Design 3 Expressive (MD3E)** 全面重写的 Android 应用。

> 本项目是 [Hitomatito/RedmagicCooler](https://github.com/Hitomatito/RedmagicCooler) 的完全重写版本，非官方应用，与 Nubia/RedMagic/ZTE 无关。
>
> **本 fork 已从「蓝牙散热器控制器」改为「本机内置风扇控制器」**：控制路径从 BLE GATT 换成 root sysfs（`soc:xiaomi_fan` / `odm:xiaomi_fan`），并移除了设备管理、蓝牙扫描与灯效相关界面（见下）。

## 功能

- **手动调速**：转速滑块写入 `pwm_duty`（0–99 无极调速）；连续拖动会合并为最新目标，避免每次触摸都排队一个 `su` 进程
- **风扇开关（总电源）**：关闭即停转，并**记住关闭前的运行模式**；重新开启时自动恢复到该模式（智能温控 / 满速 / 手动），不再一律退回静谧档
- **智能温控**：按本机温度曲线自动调速（分段线性插值）；与「满速模式」互斥
- **响应速度可调**：轮询间隔自定义 200–5000 ms，默认 **1000 ms**；升降过渡时间自定义 0–10000 ms，默认 **0 ms（直接到位）**，取消每轮百分比步进限制
- **满速模式**：强制满速（`target_level 4` / `pwm_duty 99`）；关闭时**回写节点**回到静谧档，不只改本地状态
- **实时遥测**：温度、转速（RPM）、占空比百分比；整批读取只起**一个** `su` 进程。读不到时不冒充旧值 —— 温度读取失败会标记「状态更新受限」并停止温控写，而不是拿 0°C 去算曲线
- **写入诚实性**：每个控制写都按退出码判定，失败时首页横幅提示「控制指令未生效」，不会把写失败显示成成功
- **运行检查**：只读探测 Root / 风扇节点 / 温度读取 / 转速读取，逐项显示通过、异常或未检测；不写任何节点
- **首次启动引导**：欢迎 → 检测 Root 权限 → 运行检查 → 设置完成
- **快捷设置磁贴（风扇控制）**：**唯一**一个磁贴，与首页「风扇开关」同义 —— 一键开关风扇总电源。关闭时记住当前运行模式，重新打开时自动恢复（智能温控 / 满速 / 手动），因此磁贴与主界面不会各管一半、状态互相打架
- **双语**：中文 / English（100% Kotlin 字符串表，无 strings.xml）
- **主题**：默认紫灰配色，支持浅色/深色与多种调色板；Android 12+ 可选壁纸动态强调色，页面/顶栏/底栏/选项背景保持固定

### 本 fork 已移除（上游功能）

上游面向红魔蓝牙散热器的下列功能在本 fork 中**已删除**，不要期待在界面里找到：

| 已移除 | 原因 |
|---|---|
| 「设备」页、蓝牙扫描 / 添加设备页 | 内置风扇不走蓝牙，没有可扫描、可连接的设备 |
| 「灯效 / RGB」页与 RGB 调色 | 内置风扇没有 RGB 硬件；`setRGB` 为空实现 |
| 「默认连接的设备」设置项 | 只控制本机风扇，不存在可选的连接目标 |
| 蓝牙 RSSI 信号强度、功率读数 | 无蓝牙链路、内置风扇不上报功率 |
| 过冷保护开关、供电限档弹窗 | 内置风扇无此硬件与协议；能力位未上报 |

底层 `ble/`、`domain/` 仍保留上游的 BLE 代码与协议常量（`CoolerTransport` 接口、若干未被界面引用的 state 文件），作为历史与参考实现存在，**界面上已无入口**。8 Pro 的 BLE 协议逆向资料仍保留在 [docs/protocol-8pro.md](docs/protocol-8pro.md)，仅供查阅，与本 fork 的运行路径无关。

> ⚠️ **已知残留**：`AndroidManifest.xml` 仍声明 `BLUETOOTH*` / `ACCESS_FINE_LOCATION` 等权限，并保留 `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />`。应用运行时只申请通知权限（`MainActivity.PermissionEffects`），并不使用蓝牙，但 `required="true"` 仍可能让无 BLE 硬件的设备在应用商店被过滤。清理这部分清单属于代码改动，本次未涉及。

## 支持的设备

| 设备 | 控制路径 | 识别方式 |
|---|---|---|
| **REDMI K90 至尊版（K90 Ultra）** | root sysfs `/sys/devices/platform/soc/soc:xiaomi_fan` | `ro.product.marketname` 不含 "k90 max" |
| **K90 Max** | root sysfs `/sys/devices/platform/odm/odm:xiaomi_fan` | `ro.product.marketname` 含 "k90 max" / "k90max" |

### 节点与命令

| 用途 | 节点 / 命令 |
|---|---|
| 风扇占空比 | `pwm_duty`（0–99 百分比；原始值 >100 时按 255 折算） |
| 目标档位 | `target_level`（0 高速 / 1 静谧 / 4 狂暴） |
| 实时转速 | `real_speed`（哨兵值 `2` 表示未更新，按停转处理） |
| 风扇支持 | `fan_support`（`1` = 硬件可用） |
| 接管开关 | `/system/bin/settings put system cooling_fan_enable 0`（夺回控制权） |
| 温度 | `thermal_zone*` 或 `dumpsys battery`，见 `FanNodes.temperatureSources` |

> 两点与参考实现 `mi-fan-control` 的差异，都是本机踩到的坑：命令必须用**绝对路径**（`su -c` 继承的 PATH 里 `settings`/`getprop` 是坏 shim），且**超时必须覆盖读输出阶段**（详见下节）。

## 接管时机（重要）

**应用启动不会改变系统风扇策略。** 接管被拆成两步：

1. **只读挂载**（`FanManager.activate(requestPrompt)`）：探测 Root、确认风扇节点可读，然后开始轮询温度与转速。**不写任何节点**，所以打开应用看到的是真实状态，而不是「已经被本应用改过」的状态。启动时由 `CoolerViewModel` 以 `requestPrompt = false`（短超时静默探测）自动发起；用户点「启用控制」时以 `true` 发起，允许等待 Root 授权弹窗。
2. **接管写**（`settings put system cooling_fan_enable 0`）**只在用户第一次操作风扇时**发生 —— 调速、风扇开关、智能温控或满速模式任一次写入前先夺回控制权，之后不重复写。

### 自定义温控曲线与息屏停转

「温控曲线」页面允许编辑温度/占空比控制点，曲线会持久化并在智能温控启动时读取；拖动点位会自动保持温度单调和占空比不下降。曲线落盘损坏时会回退默认曲线并在界面提示。设置中的「温度源」决定曲线使用的本机温度读数。

可选的「息屏停转」仅在智能温控、非充电且温度低于 42°C 时停转；温度升至 48°C 或屏幕点亮时恢复。停转使用双阈值迟滞，避免温度在边界附近反复启停。温度连续读取失败时会停止曲线写入并交还系统。

### 响应速度：轮询间隔与升降过渡时间

设置页直接输入毫秒数，按键盘完成键或离开输入框保存：

- **轮询间隔**：200–5000 ms，默认 1000 ms，决定温度采样频率。实际周期还包含读取与写入耗时。
- **升降过渡时间**：0–10000 ms，默认 0 ms；0 时本轮读到温度变化就直接写曲线目标，不再每轮只移动 5%。正值表示在指定时间内按线性比例升/降占空比。
- 过渡按轮询节拍落地，不额外启动高频写入循环；非零过渡的完成点是达到设定时长后的首个轮询。因此过渡时间小于轮询间隔时，下一轮完成。
- 中途目标改变时先推进已有过渡，再从该值向新目标过渡，避免连续温度变化导致一直停留在起点。
- 输入越界会在提交时限制到显示范围；损坏的存储值回落默认。旧 `smooth_step` 不再读取，已有合法轮询设置保留。
- 每轮读取需一次 `su`，需要改变占空比时另需写入；缩短间隔会增加开销。息屏轮询仍为 15 秒。
- 设置订阅后下一轮生效，不需要重启服务。直接到位是直接写目标占空比，物理 RPM 仍由硬件逐渐响应。

进程在息屏停转期间被杀死时，开机接收器会在恢复其它自动模式前先执行安全网：写回静谧档、开启系统接管并停止服务，避免设备以「风扇停着且系统未接管」的状态启动。

未挂载时首页不提供任何写入入口，只有只读的「运行检查」和显式的「启用控制」按钮。旧实现在**构造时**就自动接管，会立刻改变用户的风扇策略。

## 运行检查

`fan/FanChecks.kt` 定义检查模型（`FanCheckId` / `FanCheckState` / `FanCheckReport` / `runFanChecks`），全部为**只读**探针。

| 检查项 | 类别 | 通过条件 |
|---|---|---|
| Root 权限 | **必需** | `id -u` 输出 `0` |
| 风扇节点 | **必需** | `fan_support` == 1 |
| 温度读取 | **必需** | 温度节点返回可解析数值 |
| 转速读取 | **必需** | `real_speed` 返回可解析数值 |
| 本机型号 | 信息项 | `ro.product.marketname` 非空（不参与通过判定） |
| 通知权限 | 可选 | 已授权 `POST_NOTIFICATIONS`（未开启不影响风扇控制） |

Root 不可用时，后续节点探测标记为 `SKIP`（「没权限检查」与「检查了但失败」是两种状态）。必需四项未全部通过时，首页调速控件保持禁用，并保留「运行检查」入口。引导完成状态持久化在 DataStore（`SettingsRepository` 的 `onboarding_completed` → `AppSettings.onboardingCompleted`）。

## 技术栈

- **Kotlin 100%**（UI 全 Jetpack Compose；XML 仅剩 `AndroidManifest.xml` 与一个矢量启动图标）
- **MD3E**：`MaterialExpressiveTheme` + `MotionScheme.expressive()` 弹簧动效 + Expressive 形状阶梯
  - 依赖 `androidx.compose.material3:material3:1.5.0-alpha29`（经 `compose-bom-alpha:2026.09.01` 托管）
- **架构**：domain / fan / data / service / ui 分层，`StateFlow` 驱动 UI；`FanManager` 实现 `CoolerTransport`，使既有 Service / Tile 层只需少量适配
- **DataStore** 持久化（外观设置、引导完成状态、终端档案）
- Android 12 以下走旧权限路径，兼容 API 24–37

## 命令执行可靠性

`fan/RootCommand.kt` 的 `executeProcess` 在**独立线程**上并发抽取 stdout/stderr，超时用 `waitFor(timeout, unit)`：

- **超时必须覆盖「读输出」阶段**。旧实现先阻塞读到 EOF 再 `waitFor`，一旦 `su` 在等授权弹窗（既不输出也不退出）就永远走不到超时分支，引导页与轮询一起卡死。
- `RootResult` 暴露 `exitCode` / `timedOut` / `fused`，让调用方能判定「写成功」还是「命令没执行、写被拒绝」；`writeInt` 即以退出码判定。
- 温度读取失败返回 `null` 而非 `0` —— 旧实现返回 0 会让智能温控按 0°C 把转速压到最低；`null` 与真实的 0 是两件事。
- 连续失败达到阈值后熔断 5 秒，避免 root 异常时高频起进程。

## 已知问题：图标库部分矢量坐标损坏

图标来自 [`com.composables` Material Symbols](https://github.com/composablehorizons/compose-icons)（`icons-material-symbols-rounded[-filled]`，当前 2.2.1）。**该库有未修复的上游 bug：部分图标的 path 坐标生成错误——Y 整体偏移出 viewport，渲染时图形缺失/越界/错位**，只在个别图标上出现，不代表全部。

- 上游 open issues：#25 Strange icons behavior、#13/#16/#17 同类、#15 Settings icon is distorted、#27 Solid icons fill inner cutouts（均为 open，2.2.1 已是 Maven Central 最新版）
- 已确认受害的图标（含上游用户报告）：`arrow_back`、`check`/`done`/`check_circle`/`check_small`/`verified`（对勾系全家）、`style`、`arrow_back_ios`、`keyboard_arrow_left`、`chevron_left`、`navigate_before`、`turn_left`、`chat_bubble`、`energy_savings_leaf`、`disabled_by_default`、`tag` 及部分 outlined 变体

**根因**：composables 搬运 Google Symbols 素材时，把部分图标的 path Y 坐标整体减了 960（图形被移出 960 viewport，渲染时缺失/越界），官方仓库 `google/material-design-icons → symbols/android/*/materialsymbolsrounded/*_fill1_24px.xml` 里同一图标的坐标是正常的。

**规避策略**：新增图标前先用脚本检查 path 是否出界（任何绝对坐标落在 0–960 viewport 之外即判定损坏）；遇损坏的直接采用 **Google 官方仓库同图标 XML**（`res/drawable/ms_*.xml`），仅去掉 `android:tint=?attr/colorControlNormal` 一行（AppCompat-free 项目，由 Icon composable 着色），坐标保持官方原样。当前界面仍在使用、且已确认损坏并以官方 XML 替代的图标：`arrow_back`→`ms_arrow_back_fill1_24`（关于页返回，保留官方 `autoMirrored`，顺带修复 RTL）、开关/下拉及检查页对勾 `check`→`ms_check_fill1_24`、设置页调色板 `style`→`ms_style_fill1_24`。

## 目录结构

```
app/src/main/java/io/github/wxmyyds/coldfront/
├── domain/    设备类型(含内置风扇)、模型、遥测解析
├── fan/       内置风扇：Root 节点访问、命令执行、控制状态机、运行检查
├── ble/       CoolerTransport 接口与上游 BLE 实现（界面已无入口，保留参考）
├── data/      DataStore 仓库、档案 JSON 编解码
├── service/   前台服务、开机恢复、快捷磁贴
└── ui/        MD3E 界面(主题/双语/各屏)
```

界面文件（`ui/`）：

| 文件 | 说明 |
|---|---|
| `MainActivity.kt` | 入口，导航与两个顶层页的宿主；未完成引导时显示 `OnboardingScreen` |
| `TopLevelPager.kt` | 两个顶层页的横向分页（`pageCount = 2`） |
| `HomeScreen.kt` | 风扇页：温度英雄卡、风扇开关、智能温控、满速模式、转速滑块 |
| `SettingsScreen.kt` | 设置页：外观 / 通用 / 运行检查 / 关于 |
| `OnboardingScreen.kt` | 四步引导；设置页「运行检查」以 `isRecheck = true` 复用同一页 |
| `AboutScreen.kt` | 关于页 |

**已删除**：`AddDeviceScreen.kt`、`DevicesScreen.kt`、`RGBControlScreen.kt`。

## 未验证事项

本次改动**只做了编译与单元测试，未做实机验证**。以下行为在真机上仍待确认：

- **接管时机**：启动只读挂载、首次操作才写 `cooling_fan_enable 0` 的新路径未在真机走通全流程（包括 KernelSU / Magisk 授权弹窗下的 30s 超时行为）。
- **节点读写**：`pwm_duty` / `target_level` / `real_speed` / `fan_support` 在 K90 Ultra 与 K90 Max 上的实际可写性与取值范围未经实机对照；`real_speed` 哨兵值 `2`、`pwm_duty` 原始值 >100 按 255 折算等约定均来自参考实现。
- **温度链路**：`thermal_zone*` / `dumpsys battery` 各温度源在不同机型上的可用性与单位（毫摄氏度）未实机核对；温控曲线（30–90°C 分段）取自参考实现默认值，未按本机实测标定。
- **运行检查**：四项必需项的通过/失败判定在真机上的表现未验证；Root 被拒时 `SKIP` 状态的界面呈现未验证。
- **引导与持久化**：首次启动四步流程、`onboarding_completed` 落盘与重启后跳过引导未实机验证。
- **上游 BLE 残留代码**：`ble/` 与协议相关代码已不在运行路径上，但也未做完整清理验证。
- **清单未清理**：`AndroidManifest.xml` 仍声明 BLE / 定位权限与 `bluetooth_le required="true"`（见「本 fork 已移除」下的提示）。这些声明不影响现有编译与单测，但无 BLE 硬件的设备上的实际表现未验证。
- **界面与交互**：两个底部标签、四步引导、「运行检查」复用路径、满速/智能互斥的禁用态等 UI 行为均只经编译与单测，未在真机上点过。

## 构建

要求：JDK 21、AGP 9.4.1 / Kotlin 2.4.20、Gradle 9.6.0、compileSdk 37.1、minSdk 24。

```bash
git clone https://github.com/wxmyyds/ColdFront.git
cd ColdFront
./gradlew assembleDebug
```

> 正式验证使用 GitHub Actions：Debug/Release 单元测试、完整 Android Lint 和双变体 APK 构建；测试与 Lint 报告随构建产物上传。硬件连接和实际 UI 效果仍需实机回归。
>
> MD3E 完整 API 需要 material3 alpha 线（已在 `gradle/libs.versions.toml` 配置 `compose-bom-alpha`）。

### 版本号规则

版本名形如 **`yy.MM.dd-n`**，例如 `26.10.11-3` —— 年月日 + **当天第几版**。

版本号在**构建时按当天系统日期生成**，源码里没有 `versionName`/`versionCode` 字面量：同一天多次构建自动递增 `n`，跨天自动归 `1`，不需要手工改任何东西。当天计数落盘在 `app/build/version-counter.properties`（构建产物目录，`clean` 后归零是预期行为）。`versionCode` 由版本名映射而来（`yymmdd * 100 + n`，例 `26.10.11-3` → `26101103`），保证单调递增，否则系统会以 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝安装。

发布流程读取的是构建产物 `BuildConfig.VERSION_NAME`，而不是源码字面量：

```bash
grep -oP 'VERSION_NAME\s*=\s*"\K[^"]+' \
  app/build/generated/source/buildConfig/debug/io/github/wxmyyds/coldfront/BuildConfig.java
```

### Release 签名

发布 APK 由 CI 用固定发布密钥签名。密钥库与口令**不入库**，只存在本机备份与 GitHub Secrets：

| Secret | 内容 |
| --- | --- |
| `COLDFRONT_KEYSTORE_BASE64` | `base64 -w0 ~/.keystores/coldfront-release.jks` |
| `COLDFRONT_KEYSTORE_PASSWORD` | 密钥库口令 |
| `COLDFRONT_KEY_ALIAS` | `coldfront` |
| `COLDFRONT_KEY_PASSWORD` | 密钥条目口令（PKCS12 下与密钥库口令相同） |

发布密钥 SHA-256 指纹（可用于校对下载的 APK 签名）：

```
C7:55:6E:0E:4B:18:C0:17:F9:83:88:D3:04:AF:26:97:24:BE:2D:39:3B:03:43:AF:21:A1:1D:5E:9E:A1:C9:63
```

- `release-stable.yml`：缺任一 Secret 直接失败，不会静默发出未签名 APK（未签名 APK 实际无法安装）；发布前用 `apksigner verify` 校对签名。
- `build.yml`：不注入密钥（PR / fork 拿不到 Secrets），仍产出未签名 APK 供校验。
- 本机签名构建（可选）：把 `~/.keystores/coldfront-keystore.properties` 中的 `coldfront.*` 四项放进 `~/.gradle/gradle.properties`，或用环境变量 `COLDFRONT_KEYSTORE_*` / `-P coldfront.*` 传入。环境变量优先于 Gradle 属性。
- 未配置凭据时 `assembleRelease` 回退为未签名构建，fork 仍能编译。

> ⚠️ **密钥库与口令丢失后，无法再为已安装的应用发布更新**（Android 要求更新包与已装应用同签名）。请把 `~/.keystores/coldfront-release.jks` 与口令离线备份到两处以上。

## 许可

基于 MIT 许可的 [Hitomatito/RedmagicCooler](https://github.com/Hitomatito/RedmagicCooler) 重写。
