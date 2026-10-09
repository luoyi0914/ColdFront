# 红魔散热器 8 Pro BLE 接口

> **本 fork 的运行路径已不经过本文件描述的任何 BLE 协议。** ColdFront 现在控制的是 REDMI K90 至尊版**内置风扇**（root sysfs，见 README「接管时机」与「节点与命令」），设备管理 / 蓝牙扫描 / 灯效页面均已删除。本文件作为上游 8 Pro 散热器的逆向记录保留，仅供查阅，其中的 UUID、特征值语义与校准结论均**未随本次改动复核**。
>
> 来源 1:`cn.nubia.externdevice` 官方应用 smali 反编译(`cn/nubia/device/bluetooth/jacket8pro/`)。
> 来源 2:**实测可用的第三方控制 App**(`com.magcooler.cpucontrol`,特征语义带中文日志)。
> 两者冲突处以实测为准,已标注。
>
> **校准待核对**：本文件的实测资料记录“无 −6°C 偏移”，但当前应用沿用官方资料的 −6°C 校准。本轮全局代码审查未获得新的硬件对照数据，因此未改动该行为；不能把此处协议描述视作已完成全型号校准验证。

## 1. 设备识别(广播)

| 项 | 值 |
|---|---|
| 广播 Service UUID | `00004a41-0000-1000-8000-00805f9b34fb`(家族标记,须在 ServiceUuids 列表中) |
| 厂商数据(Manufacturer Specific Data) | Company ID **0x08CA(2250)**,payload 前 2 字节 = `[mainType, subType]` |
| 8 Pro 识别码 | **mainType = 0x05, subType = 0x08** |
| 全系识别码 | 3=(5,3) 4=(5,4) 5/5Lite=(5,5) 6/6Pro=(5,6) 8Pro=(5,8) |
| OTA 模式名 | BLE 名含 `PPlusOTA`(识别放行) |
| Device 枚举 | `JACKET8Pro`,设备代码 `rm_charge_clip8_pro` |

### 实机抓包样例(8 Pro,已验证)

```
名称:   RM Magcooler 8pro
MAC:    D0:00:FF:40:15:ED
RSSI:   -43 dBm
厂商数据 0x08CA:  05 08 02 50 02 00 00 00 00 00 00
服务 UUID:       00004a41-0000-1000-8000-00805f9b34fb
```

即 MSD 前 2 字节 `05 08` 与逆向结论完全一致(第 3 字节 0x02、第 4 字节 0x50 用意未知,
0x50 = 80 恰为风扇 raw 上限,疑为能力/状态字段,待验证)。

## 2. GATT 结构

⚠️ **特征值遍历所有服务收集,但优先取官方主服务 `d52082ad-e805-9f97-9d4e-1c682d9c9ce6` 的实例**(ColdFront 遍历全部服务、主服务排在最前,按 UUID `putIfAbsent` 先到先得)。依据:官方 `buildServiceAndCharacter` 从该服务取特征,且 `onCharacteristicRead`/`onCharacteristicChanged` 只处理该服务的回调。其它服务可能暴露同 UUID 但语义不同的特征。实测 8 Pro 的 0x1013 位于该服务下(`svc=d52082ad-e805-9f97-9d4e-1c682d9c9ce6`)。

| 特征值 | 语义(实测) | 属性 |
|---|---|---|
| `00001011-...` | **散热总开关(官方 HALL):写 0x02 开 / 0x03 关;通知 2=开 3=关。不写 ON 风扇不转!官方连接时只 `queryHall` 读回,不写** | R/W/Notify |
| `00001012-...` | **风扇调速:单字节 raw(8 Pro 40–80 共 8 档)** | R/W |
| `00001013-...` | **灯光状态/控制:[mode][R][G][B]**;官方 `queryLight` 通过 GATT 主动读取,不写查询字节 | R/W/Notify |
| `00001014-...` | **背夹温度:单字节有符号 °C;固件 8.4.7 为 [0x04, 温度] 包。无 -6 偏移** | Notify |
| `00001015-...` | **状态包:[tag, byte0, byte1, byte2]:tag 0x04 → 温度;0x05 → 负载;0x07 → 供电功率限档索引(见 3.5);0x08 → 大端 RPM;0x09 → 功率 W** | Notify |
| `00001016-...` | LOGGER 日志(UTF-8) | R/Notify |
| `00001017-...` | **Boost/破坏神(超频):写 0x01/0x00** | R/W |
| `00001018-...` | **智能温控:写 0x01 开 / 0x00 关;通知 1=开** | R/W/Notify |
| `00001019-...` | 旧版风扇转速 | R |
| `0000101a-...` | 旧版功率 | R |
| `0000101c-...` | **风扇转速(官方 8.4.7):大端 16 位 RPM** | Notify |
| `0000101d-...` | **功率(官方 8.4.7):byte0 = W** | Notify |
| `0000101f-...` | **温度告警/保护配置:**至少 4 字节;byte0 bit0..3 为保护位,byte1 事件 ID,byte2/3 阈值 | R/W |

写类型:`WRITE_TYPE_DEFAULT(2)`。**操作必须串行**(Android BLE 同一时刻仅一个在途操作)。

## 3. 核心命令

### 3.1 风扇调速(0x1012)
- 写**单字节 raw**,8 Pro 范围 **40(0x28)–80(0x50)**,8 档
- 换算:`raw = 40 + percent × 40 / 100`
- UI 档位映射(官方 Companion.d):40–42→0档,43–48→1,49–54→2,55–60→3,61–66→4,67–70→5,71–74→6,≥75→7
- 读取/Notify 回读当前 raw

### 3.2 自动模式(0x1018)
- 开:**写 `0x01`**(官方日志"8PRO 背夹 writeAutoOn 写1")
- 关:**写 `0x00`**("writeAutoOff 写0")

### 3.3 温度(0x1014 Notify)
- 单字节**有符号** °C(合法范围 −40..80,越界丢弃)
- 固件 8.4.7:**[0x04, 温度]** 多字节包,取 byte[1]
- ~~显示 = raw − 6~~(官方 App 有此校准,实测 App 无偏移;以实测为准)

### 3.4 灯光(0x1013)
- 官方 8 Pro **只有读路径**:`Jacket8ProViewModel.Z0()`(queryLight) → `Jacket3ManagerV2.i()` → `Jacket8ProProcessor.i()`(注册 LIGHT_R) → 任务队列 → `f2/a.t()` → `f2/a$h.m()` = GATT `readCharacteristic(0x1013)`;连接后(onConnected)自动触发一次。
- `f2/a$h.n()`(写 `[0x11]`)对应的 LIGHT_W(`Jacket8ProProcessor.o()`)在 8 Pro **无任何调用者**(全仓只有 jacket3 的 Presenter 调 `Jacket3ManagerV2.o()`)。ColdFront **不写 `[0x11]`**:实测写入后 `read` 回读 `11 00 00 00`(写入值回声),会污染灯效。
- 特征绑定优先官方主服务 `d52082ad-e805-9f97-9d4e-1c682d9c9ce6`(官方 `onCharacteristicRead`/`onCharacteristicChanged` 只处理该服务);其它服务可能暴露同 UUID 但语义不同的特征。
- 灯效未回读到前,仅在 READ 通道可用时按最短4s间隔尝试回读；read超时(2.5s)会保留可排空标记。只有旧回调到达并被丢弃后才允许新读，不能靠等待4s认定安全；长期无回调时提示重连恢复。
- 官方数据处理器不剥读回数据的前缀,直接以 byte[0] 作为 mode;常亮/单色呼吸颜色来自 byte[1..3]。
- 未知模式兑底:官方 `refreshLightModeView` 读到非 1/2/3/4/6 的字节且数组非空时,会下发默认灯 `n0.a()=[0x01,0x00,0x00,0x00]`(炫彩)。ColdFront 对齐该行为:仅当回包 byte0 不属于任何已知模式(非 1/2/3/4/6)且非空时下发同一默认灯(3s 节流,每次连接最多 3 次,写超时不使整个会话失败,但仍保留旧回调归属标记,不得定时强行重用通道)。已知模式但缺颜色字节(如 `[0x04]`)按已知模式处理,不触发兑底。
- 模式命令 4 字节 `[mode][R][G][B]`:

| mode 字节 | 语义(官方字符串) |
|---|---|
| `0x01` | 炫彩 colorful |
| `0x02` | 呼吸 breathe |
| `0x03` | 呼吸 breathe(带 0xFF 变体) |
| `0x04` | 常亮 all_on |
| `0x05` | 场景灯效(官方仅由场景联动 `sendScenarioCmd` 下发;8 Pro 灯效页视为未知,ColdFront 不提供该选项) |
| `0x06` | 关闭 off |

- 场景下发(官方 `sendScenarioCmd`):light=1→`[01,00,00,00]`,2→`[02,00,00,00]`,3→`[01,FF,00,00]`,4→`[03,FF,00,00]`,5→`[05,00,00,00]`(前四项对应炫彩/呼吸/常亮/关闭;ColdFront 不暴露 light=5)
- 自定义颜色:`[R, G, B]`(3 字节)或 `[R, G, B, 0]`(4 字节,官方示例 `[FF,00,00]`、`[FF,FF,00,00]`)

- 查询当前保护配置:主动读取 0x101F,至少 4 字节 `[flags,eventId,high,low]`;byte0 bit2 表示过冷/冷凝保护。
- 配置状态按特征分别主动读取;回调将原始数据路由到 `Jacket8ProDataHandler`/ViewModel,再由 LiveData 更新 UI。
- 1013 使用 `queryLight` → `readCharacteristic`;1011 hall、1012 fan、1017 overclocking、1018 auto 与 101F 温度保护各自回读,不会用本地预设代替回读。
- ColdFront 对特征不可读或返回无效数据时不伪造状态,相关控制项保持隐藏/未知。
- 配置回读是解锁对应控制项的前置条件:连接时首次可选回读超时保留可排空标记；迟到回调只排空、不确认旧值，后续安全回读或有效通知才确认配置。旧回调尚未排空或通道已永久隔离时不继续定时提交读请求，并提示「状态更新受限」；永久隔离需重连恢复。

### 3.5 供电功率限档(0x1015 标签 `0x7`)

**官方 `Jacket8ProDataHandler`(`cn/nubia/device/bluetooth/jacket8pro/b.smali` 的 `j(...)`)**:0x1015 通知的包格式是 `[tag, byte0, byte1, byte2]`(tag = byte0,其后 3 字节不足补 0),标签 `0x7` 时调用 `onFanLimit(byte0)`。该字节是**官方档位索引(0–8)**,不是 raw:

- "不限档"值 = **8**(`Jacket8ProManagerV2$Companion.b()`,即静态字段 `r0`);**小于 8 即充电器供电功率不足**。
- 官方档位索引 → 允许的最高 raw(`Jacket8ProManagerV2$a.e(I)`):

| 索引 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| raw | 40 | 46 | 52 | 58 | 64 | 68 | 72 | **76** | 80 |

反向 `d(I)`(raw → 索引)的分桶边界为 40/43/49/55/61/67/71/74/78,故 raw 76 与 80 是两个不同档位。
**本应用 UI 的 1–8 档没有对应 raw 76 的档位**(第 8 档代表 raw 80),因此限档索引换算成 UI 档位上限时按"raw 不超过限档 raw"取最高档位:索引 7 → 档位 7(raw 72),索引 6 → 档位 7,索引 5 → 档位 6,……,索引 0 → 档位 1。

官方 `Jacket8ProActivityV3.l6/N4` 在限档值变化(且 < 8)时的动作,即本应用对齐的行为:

1. 用 `R$string.fan_limit_tips` 提示(Toast 全局 1.5s 去重;本应用改为一次性确认弹窗,同一限档值只弹一次,限档变化或解除限档后再次受限才重新提示)。
2. **自动关闭破坏神**:若 `sw_super_mode`(`i0->N`)为开,调用 `CustomSwitchView.c()` 取消选中 → 写 `0x1017 = 0x00`。
3. **限制档位**:`LimitedSeekBar.setMaxSelectableProgress(限档)` + `TickDividerView.setMaxSettableIndex(限档)`,滑条 `max` 仍是 8,超出上限的拖动会被 `LimitedSeekBar$a.onProgressChanged` 弹回并再次提示。
4. **压低当前档位**:若当前 progress 超过限档,`setProgress(限档)` → 回调 `onProgressChanged` → `Jacket8ProActivityV3$b` 把 `e(限档)` 作为 raw 通过 `viewModel.y1(raw)` **实际下发**(不是只改 UI)。
5. **否决开启破坏神**:破坏神开关的 `OnBeforeCheckedChangeListener`(`u5`)在"开关当前是关"且 `限档 < 8` 时返回 false(同时提示)。

**智能温控不参与该限制**:官方在限档路径里从不碰 `i0->O`(`sw_temp_control`,0x1018)。

注:官方另有 `onOutputHasLoad`(标签 `0x5`)/ `refreshOutputHasLoad` 一条联动"输出带载"的路径(强制开温控 + 关破坏神 + `R$string.output_has_load_tips`),但该路径在 V3 上是死代码:其 LiveData(`Jacket8ProViewModel.u0()`)全仓无写入者,唯一入口 `C6(B)` 也无调用者。本应用不对应实现。

### 3.6 智能温控(0x1018)与破坏神(0x1017)互斥

官方两个开关(`i0->O` = `sw_temp_control`,`i0->N` = `sw_super_mode`)**是双向互斥**的,靠"禁用 + 强制取消选中对方"实现,不是靠点击前校验:

| 方向 | 官方实现 | 结果 |
|---|---|---|
| 破坏神开 | `Jacket8ProActivityV3.n5`:先 `O.c()` + `switchAutoTempControl()`(写 `0x1018=0x00`),再 `o6(true)` → `b6(false)` 把温控开关禁用+取消选中,最后写 `0x1017=0x01` | 温控关且被禁用 |
| 温控开 | 温控 LiveData 观察者 `G4` → `m6(!温控)` 把破坏神开关禁用+取消选中;`CustomSwitchView.setChecked(false)` 触发 `n5` 的 else 分支 → `E1()` 写 `0x1017=0x00` | 破坏神关且被禁用 |

两条规则在 `j6`/`k6`/`refreshFanAutoControlUI` 等刷新路径上重复套用。唯一的"点击前校验"(`u5`)只用于供电限档,与互斥无关。

另外 `sw_hall_new`(0x1011)与这两个开关之间**没有**任何联动。

## 4. 连接流程(官方)

1. 扫描(过滤 0x4a41 + MSD 0x8CA)→ `connectGatt(autoConnect=false)`
2. `discoverServices` → `buildServiceAndCharacter`:逐个 getCharacteristic(0x1011…0x101f)
3. 订阅特征支持的通知,并按配置项主动读取
4. `onCharacteristicRead`/`onCharacteristicChanged` → 数据处理器按服务和特征分发 → ViewModel LiveData → Activity 观察并刷新 UI

### 4.1 官方连接时**不写**散热总开关(0x1011)

`Jacket3ManagerV2.onConnected` 依次只发**查询**(全部为读),没有一处写 0x1011:
- L938 `"onConnected do queryLight"` → `i()`(LIGHT_R)
- L1086-1092 `"onConnected hall do query Hall"` → `n()`(HALL_R)
- L905 overClocking、L923 fan、L1053 temperature 同为 query

HALL/W(0x1011)的写入入口 `Jacket3ManagerV2.e()/j()/l()`,其调用点**只有 UI 层**:
- `e()`: `ui2/jacket3/Jacket3PresenterImlV2.smali:952`
- `j()`/`l()`: `Jacket3PresenterImlV2.smali:1135` / `:386,:1181`,以及 `Jacket8ProManagerV2.smali:1401` / `:1423`(`invoke-super`,源头仍是 8 Pro 的 ViewModel/Activity UI)

**官方 ON/OFF 字节**与 ColdFront 常量一致:
- `Jacket8ProManagerV2.j()`(L1385)取 `f2/a$g.m()` → 字段 `h` = `[0x03]`(OFF)
- `Jacket8ProManagerV2.l()`(L1407)取 `f2/a$g.n()` → 字段 `g` = `[0x02]`(ON)
- `Jacket8ProViewModel.B1()` 日志 `"writeHallOff"` → `j()`;`D1()` 日志 `"writeHallOn"` → `l()`

**官方唯一额外路径(防御性)**:`Jacket8ProActivityV3.l6()`(L8767)观察 `y0`(LiveData `H`,由 `Jacket8ProViewModel.onHallRead` 取 `value[0]` 写入,L5601;初值 `Integer.MIN_VALUE`):`y0==2`→UI 开、`y0==3`→UI 关、**`y0==0` → `D1()`(writeHallOn,下发 0x02)**(L8879)。

**ColdFront 现状与结论**:与官方连接流程一致——连接时**只回读 0x1011**(在 `configurationUuids` 内),不主动写 ON;`main` 旧版的"每次连接写 0x02"无官方对应,已移除。官方 `y0==0 → writeHallOn` 这条兜底**未实现**(无证据表明真实设备会回报 `byte0==0`,且它会实际改变设备状态),仅记录于此。

## 5. 与旧协议(1–6 代)的差异

| 项 | 旧(原 RedmagicCooler 项目) | 8 Pro 实测(本次逆向) |
|---|---|---|
| 识别 | ServiceData(0x4A41) payload 16 字节 UUID | **MSD 0x8CA 的 [mainType, subType]** |
| 风扇 raw | 40–200 | **40–80(8 档)** |
| 自动模式 0x1018 | 一律写 0x00 | **开=0x01,关=0x00** |
| 温度 | data[0] | 1014 / 1015 温度包；实测资料无偏移，当前应用仍保留 −6°C 校准（待核对） |
| RGB | `[effect][R][G][B]` | 模式命令 4 字节 + 自定义 `[R,G,B]` |
