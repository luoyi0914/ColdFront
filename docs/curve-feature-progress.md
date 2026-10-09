# 自定义温控曲线 — 实现进度与交接

> 状态：**主体已实现并通过测试，尚未收尾**（开机安全网 / README / APK 未做）。
> 设计依据：[design-custom-fan-curve.md](design-custom-fan-curve.md)（9 条已确认决策）。
> 本文只记录"做到哪儿了"和"下一步做什么"，设计理由看设计文档。

---

## 1. 验证证据（最后一次全量运行）

| 项 | 值 |
|---|---|
| 构建 | `BUILD SUCCESSFUL` |
| 测试类 / 用例 | 42 类 / **370 用例** |
| 失败 / 错误 | **0 / 0** |
| 基线（改动前） | 284 用例（曲线功能 +66 / 轮询参数 +20） |

新增的 8 个测试类：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `fan.FanCurveTest` | 23 | 插值边界与截断、结构性约束、预设、危险判定、JSON 往返/损坏回退 |
| `fan.ScreenOffPolicyTest` | 11 | 不进入停转的各条件、6°C 迟滞带不抖动、亮屏必回 ACTIVE |
| `data.SmartCurveSettingsTest` | 13 | 「缺失 vs 损坏」可区分、温度源校验、息屏开关默认关 |
| `ui.CurveEditorStateTest` | 16 | 拖动钳制（X 单调 + Y 不减）、边界扫查、脏标记、预设、危险 |
| `ui.i18n.TemperatureSourceNamesTest` | 3 | 名称表与 `FanNodes.temperatureSources` 顺序对齐 |
| `fan.FanTuningTest` | 8 | 默认值必须是合法选项、选项表有序无重复、非法值回落、息屏不更勤 |
| `data.PollTuningSettingsTest` | 8 | 默认 1.5 秒 / 5%、每个选项往返、非法值不落盘、读取兜底 |
| `ui.i18n.PollTuningNamesTest` | 4 | 名称表与 `FanTuning` 选项表长度对齐、无空名/重名 |

---

## 2. 已完成

### 纯逻辑层（无 Android 依赖，可单测）

- `fan/FanCurve.kt` — 曲线模型、校验、插值、危险判定、预设、JSON 编解码。
  `FanCurve` 无法被构造成不良构状态，因此**上游"乱序曲线 → 区间落空 → 返回 0 静默停转"那条路径不可达**。
- `fan/ScreenOffPolicy.kt` — 息屏停转双阈值状态机（`ACTIVE` / `STOPPED` / `HANDED_BACK`），42°C 进、48°C 出。
  只在智能温控下参与；充电、温度不可读、亮屏一律不进入停转。
- `ui/CurveEditorState.kt` — 编辑器草稿状态与拖动钳制；`tempBounds` / `dutyBounds` 供滑条直接限定范围。

### 持久化

- `data/AppSettings.kt` — 追加 `smartCurveLoad`（保留 `CurveLoad` 以区分"缺失"和"损坏"）、`temperatureSource`、`screenOffStopFan`。
- `data/SettingsRepository.kt` — 键 `smart_curve_json` / `temperature_source` / `screen_off_stop_fan`；
  `setSmartCurve` / `repairSmartCurve`（删除而非回写默认）/ `setTemperatureSource` / `setScreenOffStopFan`；
  温度源下标以 `FanNodes.temperatureSources` 为唯一权威做校验。

### 控制层

- `fan/FanManager.kt` — 曲线改为**从持久化读**（原来是构造时硬编码的 8 点）；温度源贯通
  `readSnapshot` / `readTemperatureC`；息屏相位机（停转挂起曲线写入、交还系统、重新接管）；
  轮询节流（亮屏 2s / 息屏 15s）；温度连续 3 次不可读 → 失效降级（回静谧档 + 交还系统）。
- `services/CoolerService.kt` — 持有 `ACTION_SCREEN_ON/OFF` 广播并转告 `FanManager`，
  启动时用 `PowerManager.isInteractive` 播种真实屏幕状态（服务可能息屏时被拉起）。
- `ble/CoolerTransport.kt` — 新增 `setScreenInteractive`（**默认空实现**，上游 BLE 实现不受影响）。
- `domain/CoolerModels.kt` — `CoolerLiveState` 增加 `screenOffStopped` 与 `controlDegraded`
  （用布尔而非 `ScreenPhase`，避免 `domain` → `fan` 的依赖）。

### 响应速度可调（本轮新增）

原实现把轮询间隔与步进都写死（`POLL_INTERVAL_MS = 2000L`、`smoothStep = 5f`），
而这两项**相乘**决定爬升时间：`0%→99% ≈ (99 / 步进) × 间隔`，旧组合要爬约 40 秒。

- `fan/FanTuning.kt`（新文件）— 轮询间隔与调速步进的**唯一权威**：选项表、默认值、
  规范化、息屏间隔。纯逻辑、无 Android 依赖，可单测。
  - 轮询间隔 `500 / 1000 / 1500 / 2000` ms，**默认 1500**（比上游的 2 秒跟手，又没把
    `su` 开销翻倍）；步进 `5 / 10 / 15 / 20`，默认 5（与上游 fixed step 一致）。
  - `screenOffPollInterval()` 取 `max(15_000, 亮屏间隔)`：现选项上限远小于 15s，
    取 max 是为了将来放宽上限时不出现「息屏比亮屏采样更勤」这种反直觉组合。
- `data/AppSettings.kt` / `data/SettingsRepository.kt` — 键 `poll_interval_ms` / `smooth_step`；
  **读写两侧都规范化**（非法值既不落盘、也不被读出）。这不是洁癖：间隔会直接变成
  `delay()` 的参数，步进会变成每轮写硬件的幅度。
- `fan/FanManager.kt` — 删掉 `POLL_INTERVAL_MS` / `SCREEN_OFF_POLL_INTERVAL_MS` 两个常量，
  改为 `@Volatile pollIntervalMs` 与 `smoothStep`，由 `init` 里既有的 settings 收集器喂入。
  轮询循环**每轮重读**间隔，所以用户改完下一轮就生效，不必重启服务。
- `ui/SettingsScreen.kt` — 「轮询间隔」「调速步进」两个下拉，单独成组；用**下标**参与交互
  （与「温度源」同一套写法），落仓储时再换回真实值。
- `ui/i18n/Strings.kt` — `pollIntervalNames` / `smoothStepNames` 与选项表下标对齐，由测试钉住。

### 开机安全网（3.1 的方案已实现）

- `fan/FanManager.kt` — 抽出 `writeHandedBack()`（KDoc 注明**调用方须持有 `controlMutex`**），
  `applyScreenPhase` 的 `HANDED_BACK` 分支与 `onTemperatureUnreadable` 共用它；
  新增 `override suspend fun releaseToSystem()`，**自己取锁**（`controlMutex` 不可重入）。
- `ble/CoolerTransport.kt` — `suspend fun releaseToSystem(): Boolean = false`（默认空实现）。
- `service/CoolerService.kt` — 新增 `ACTION_RELEASE_TO_SYSTEM`，交还后 `stopSelfSafely()`。
- `service/BootReceiver.kt` — 读设置，**仅当 `screenOffStopFan` 为 true** 时先请求交还，
  再走既有的自动模式恢复。

### UI

- `ui/CurveScreen.kt` — 曲线编辑二级页：Canvas 拖点（吃掉竖向手势，不与页面滚动打架）、
  点位选择与精调滑条（范围取自 `tempBounds`/`dutyBounds`，拖不出非法值）、
  参考基线虚线、危险区着色、实时运行点、预设、保存/放弃、危险确认对话框、损坏曲线横幅。
- `ui/HomeScreen.kt` — 「温控曲线」入口行（摘要显示当前预设）；运行时横幅按优先级链
  （控制降级 > 写入失败 > 数据过期 > 息屏停转中）；**第 3 层提示**：温度正落在危险区段时持续显示。
- `ui/SettingsScreen.kt` — 「息屏停转」开关 + 「温度源」下拉 + 「轮询间隔」「调速步进」两个下拉。
- `ui/MainActivity.kt` / `ui/TopLevelPager.kt` — `AppRoute.Curve` 二级路由（`nav.push`）。
- `ui/CoolerViewModel.kt` — `setSmartCurve` / `repairSmartCurve` / `setTemperatureSource` /
  `setScreenOffStopFan` / `setPollInterval` / `setSmoothStep`。
- `ui/i18n/Strings.kt` — 28 + 8 个新成员，中英各一份。
  ⚠️ 格式串必须用**顺序** `%d`（本文件既有风格）；写成 `%1$d` 会被 Kotlin 当成字符串模板而编译失败。

---

## 3. 未完成

### 3.1 开机安全网（✅ 已实现，见 §2「开机安全网」）

原风险：`cooling_fan_enable` 是持久化的系统设置，息屏停转把风扇写到 0% 后若进程被
`SIGKILL` 且服务未被 `START_STICKY` 拉起，**重启后设备会带着"风扇停着 + 系统不接管"启动**。

实现时踩到的两个坑（照原方案落地）：

1. `controlMutex` **不可重入** —— `releaseToSystem()` 必须自己取锁，且**不能**从
   `applyScreenPhase` 的锁内调用；`writeHandedBack()` 的 KDoc 已写明"调用方须持锁"。
2. 三个写（`target_level 1` / `cooling_fan_enable 1` / `fan_mode 1`）**不能短路**：
   `&&` 会在第一个失败时静默跳过后面两个，让"只写成一半"被当成整体结果。

⚠️ **仍未在真机验证**：开机广播是否如期送达、`su` 在开机早期是否已可用、
交还后系统实际怎么转，都还没看过。

### 3.2 其余

- `README.md`：功能列表 + 「接管时机」一节已补充曲线、息屏停转与「响应速度」三节。✅
- APK 构建与实机验证（见 §5）。**未做** —— APK 已按 `arm64-v8a` 出包，但没装到真机上跑过。
- 设计文档 §13 的实机标定项（见 §5）。**未做**。

---

## 4. 构建与验证命令

```sh
# 编译
sh /storage/emulated/0/k90u/风扇/_build/build.sh :app:compileDebugKotlin

# 全量单测
sh /storage/emulated/0/k90u/风扇/_build/build.sh :app:testDebugUnitTest

# 出包
sh /storage/emulated/0/k90u/风扇/_build/build.sh :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 该 APK 已在 `app/build.gradle.kts` 里用 `ndk { abiFilters += "arm64-v8a" }` 限定为
> **ARMv8**（K90 Ultra / K90 Max 的实际架构），包内 `lib/` 只剩 `arm64-v8a/`。

> `_build/build.sh` 会把 SDK / Gradle 发行版 / `GRADLE_USER_HOME` 指向应用私有目录
> （`/data/user/0/app.dsh.mobile/files/tmp/build`），因为 `/storage/emulated/0` 是 FUSE，
> `chmod` 无效且 Java I/O 行为异常。**不要**直接把 `ColdFront` 放到共享存储上构建。

---

## 5. 风险与待实机标定

### 未经真机验证的部分（诚实声明）

- **曲线编辑页从未在真机上打开过。** 目前只保证：编译通过、纯逻辑有单测、
  拖动钳制与危险判定在 JVM 上被覆盖。**手势是否与页面滚动打架、图表布局、点击精度、
  `SegmentedRow`/`Slider` 的实际观感都还没看过。**
- 息屏停转的相位机逻辑有单测，但**真机上屏幕广播是否如期送达、交还系统后系统实际怎么转**
  都未验证。
- **开机安全网与轮询参数同样只有单测。** 设置页新增的「轮询间隔」「调速步进」两个下拉
  **从未在真机上打开过**：选项列表、当前值回显、改完是否真的下一轮生效，都要真机确认。
- 单纯从代码可推的期望值：默认组合 1.5 秒 × 5% 约 30 秒爬满（旧组合 2 秒 × 5% 是 40 秒）。
  这个数字**没有被实机计时验证过**。

### 待标定（设计文档 §13）

| 项 | 建议值 | 说明 |
|---|---|---|
| `target_level` 在"停转"中的确切作用 | — | 上游 `setGearStop()` 写 `level 0` + `pwm 0`，而 README 又把 `level 0` 标为"高速"，语义重叠；需确认停转是否仅靠 `pwm_duty 0` |
| 双阈值 | 42°C 停转 / 48°C 交还 | 迟滞 6°C |
| 各温度源典型量程 | — | 决定曲线 X 轴区间的可视化提示 |
| 预设曲线数值 | — | 静谧/性能的起始值 |
| 「热降频 vs 烧毁」文案前提 | — | SoC 内核热保护是否足以兜住"曲线压太低"，直接决定危险确认弹窗的措辞强度 |

---

## 6. 给下一个会话的接续指引

- 目标是活跃的持久目标（`goal-41477626-bfcb-477e-b530-a5b8da861bd9`，
  上限 40 轮）。会话被判定为"恢复/续接"时目标会处于**解除武装**状态，
  需要用户明确说一句"继续"才会重新武装。
- **本仓库工作区里有大量与本次功能无关的未提交改动**（整个 `fan/` 包、
  `ble/CoolerTransport.kt`、`ui/OnboardingScreen.kt` 等尚未纳入 git 的新文件，
  以及设备页/灯效页的删除）。它们是 fork 从 BLE 改造为内置风扇时的既有成果，
  **不是本次功能引入的**。因此不要不看内容就整体提交，也不要把它们当成回归。
- 开机安全网、轮询参数、README 都已落地；**剩下的唯一大事是真机验证**（§5）。
- 两个实际踩过的坑：`controlMutex` **不可重入**；字符串格式串不能用 `%1$d`。
- 新增界面图标前，先按 §7 的脚本核一遍 path 坐标 —— 该图标库有未修复的坐标损坏 bug。

---

## 7. 新增界面图标前的校验（本轮用到的方法）

`icons-material-symbols` 2.2.1 有**未修复的 path 坐标损坏**（README「已知问题」一节）。
本轮要加两个新图标，于是把判断做成了可复跑的两步：

1. 从 Gradle 缓存里的 **AAR** 取素材（`*-runtime.jar` 是空壳，取不到东西）：
   ```sh
   AAR=$(find /data/user/0/app.dsh.mobile/files/tmp/build/gradle-home/caches/modules-2 \
     -name 'icons-material-symbols-rounded-filled-android-2.2.1.aar' | head -1)
   unzip -p "$AAR" res/drawable/materialsymbols_ic_<name>_rounded_filled.xml
   ```
2. **按 SVG 路径语义**跟踪绝对坐标，判定是否越出 0–960：
   逐命令解析（`M/L/H/V/C/S/Q/T/A/Z`，大小写区分绝对/相对，`A` 只校验末位的 x/y、
   跳过半径与标志位），维护当前点并在每个点上校验。
   ⚠️ 别用"把 pathData 里所有数字取 min/max"这种偷懒办法：相对命令的增量本来就会是负数，
   那样**所有**图标都会被误判为损坏。

**校准结果**（脚本对已知坏图标必须报警，否则不可信）：

| 判定 | 图标 |
|---|---|
| 报警（与 README 已知损坏清单一致） | `arrow_back` `check` `done` `check_circle` `verified` `style` `chat_bubble` `chevron_left` `keyboard_arrow_left` `navigate_before` `turn_left` `tag` |
| 通过（含本项目已在用的，交叉验证脚本不误报） | `speed` `bolt` `schedule` `stairs` `refresh` `av_timer` `timer` `trending_up` `tune` `update` |

本轮选用并通过校验：**`schedule`**（轮询间隔）、**`stairs`**（调速步进）。
顺带发现 `sync_alt` 也在坏图标之列（README 清单里没有）。

## 8. 响应参数改为自定义毫秒

用户确认删除调速步进下拉；轮询改为输入自定义 ms；升降采用线性过渡时长而不是先等待再跳转。

- 默认轮询 1000 ms，输入范围 200–5000 ms；过渡默认 0 ms（本轮直接写曲线目标），范围 0–10000 ms。
- DataStore 保留 `poll_interval_ms`，新增 `fan_transition_ms`；旧 `smooth_step` 忽略，不能把百分点当毫秒迁移。
- 过渡使用单调时钟，按实际时间插值，在轮询节拍写入。非零时长的完成落在到期后的首轮，物理 RPM 不保证瞬间达到。
- 新目标到来时先推进旧过渡再重新定起点，避免每轮目标变动使插值一直停在起点。
- 温控计算、节点写入、状态发布同在控制锁内；读不到温度、息屏策略与写失败反馈保留。
- 设置数字输入按完成键或失去焦点提交；默认及越界、线性升降、存储独立性通过单测检查。
