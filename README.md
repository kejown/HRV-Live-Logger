##
HRV Live Logger
一个用于实时心率和 RR 间期监测的安卓 App，专为软件生态不完善的心率带设计，支持直接导出至 Kubios HRV

## 0. 支持两种协议

| | ANT+ | BLE |
|---|---|---|
| 服务发现 | ANT Profile 120 + Page 4 | GATT 心率服务 `0x180D` / 特征 `0x2A37` |
| 数据单位 | heartBeatEventTime tick（1/1024 s） | RR-Interval uint16（1/1024 s）**同源** |
| 系统依赖 | 必须有 `com.dsi.ant.service.socket` + ANT 硬件 | Android 原生 BLE，无额外依赖 |
| 丢拍检测 | 有 `heartBeatCount`，能**确定**丢了几拍 | **没有计数器**，只能做生理范围过滤 |
| 设备覆盖 | 少数带 ANT 芯片的机型 / 外接适配器 | 几乎所有 Android 手机 |

两条路最终喂给 Room 的 `rrMs` 完全等价，导出格式一致，Kubios 无差别导入。
App 内「协议」按钮可运行时切换，默认 BLE。

**为什么默认 BLE**：ANT 需要系统级 ANT HAL，这个层由芯片固件与厂商决定，
用户态装不了。多数国产新机（含小米/Redmi 大部分型号）`ANT HAL` 是 `Not Found`，
此时 ANT 路径物理上不可用，而 BLE 一定能用。

---

## 1. 工程结构

```
app/src/main/java/com/rrlogger/
├── ant/
│   ├── RrCalculator.kt           回绕/换算/生理过滤的纯函数 + Page4RrParser 有状态解析器
│   ├── AntHeartRateSource.kt     ANT 数据源抽象（SDK 缺失时可编译、可降级）
│   ├── SdkAntHeartRateSource.kt  通过反射驱动官方 AntPlusHeartRatePcc
│   ├── AntAvailability.kt        ANT 服务/插件服务安装检测
│   └── AntPlusManager.kt         连接管理 + 统计 + 有效 RR 流
├── data/
│   ├── Session.kt / RrRecord.kt  Room 实体
│   ├── Daos.kt                   SessionDao / RrRecordDao
│   ├── AppDatabase.kt            Room Database
│   └── RrRepository.kt           唯一数据访问入口
├── service/RecordingService.kt   前台服务：持有 ANT 连接，逐条写库，维护会话统计
├── export/RrExporter.kt          导出 Kubios 兼容 txt 到公共 Downloads
├── ui/                           MainActivity + MainViewModel
└── util/                         TimeFormat / Logs
```

单元测试：`app/src/test/java/com/rrlogger/`
- `RrCalculatorWrapTest` — 回绕计算（含文档要求的 65000→500 = 1036）
- `PhysiologicalFilterTest` — 300/2000ms 边界内/外
- `BeatDropTest` — 丢拍检测、beatCount 8 位翻转、reset 语义

---

## 2. 如何配置 ANT+ SDK 依赖（重要）

官方 ANT+ SDK **不发布到任何公开 Maven 仓库**（maven central / Google Maven / 阿里云镜像都查过，全 404）。
所以工程不写坐标，而是：

```
app/libs/antpluginlib_3-9-0.aar     ← 本工程已内置这一份
```

`app/build.gradle.kts` 里是：

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

### 下载地址（2026 年现状）

thisisant.com 的内容已**整体迁移到 Garmin 开发者平台**，旧的
`thisisant.com/developer/ant-plus/ant-plus-sdk/` 会自动跳到 `developer.garmin.com/ant-program`，
而且站点是 JS 渲染的，直接翻页面找不到下载按钮。真实的直链是：

```
https://developer.garmin.com/downloads/ant/Android_API.zip
```

下载后解包结构：

```
Android_API.zip
├── ANT_Android_SDK_v.C.B7.zip          ANT 底层协议栈（心率带 App 不需要）
├── Android_ANTEmulatorPackage_2-0-0.zip
└── AntPlus_Android_SDK_v.P.B7.zip      ← 要的是这个
    └── ANT+_Android_SDK/
        ├── API/antpluginlib_3-9-0.aar          ← 放进 app/libs/
        ├── API/AntPluginLib_Javadoc_3-9-0.zip   ← 签名参考，改反射前必看
        └── Sample/...                           官方示例源码
```

一行命令搞定：

```bash
curl -LO https://developer.garmin.com/downloads/ant/Android_API.zip
unzip -o Android_API.zip 'Android_API/AntPlus_Android_SDK_v.P.B7.zip'
unzip -o Android_API/AntPlus_Android_SDK_v.P.B7.zip -d antplus
cp 'antplus/ANT+_Android_SDK/API/antpluginlib_3-9-0.aar' app/libs/
```

### 没放 AAR 会怎样

App 依然**能编译、能安装、能启动**，只是顶栏出现红色横幅
「缺少 ANT+ SDK：请把 antpluginlib_*.aar 放入 app/libs/ 后重新构建。」，
「开始记录」保持禁用 —— 不会崩。

### 为什么用反射，以及踩过的坑

反射是为了让工程在没放 AAR 时也能编译。但 ANT+ SDK 的 API 和网上流传的老示例**差别很大**，
本工程的反射层逐条用 `javap` 对 `antpluginlib_3-9-0.aar` 核对过：

| 项目 | 常见的错误写法 | 3.9.0 的真实情况 |
|---|---|---|
| 连接入口 | `requestAccess(Context, r, r)` 三参数 | **不存在三参数版**。可用的是 `(Context, int deviceNumber, int proximity, r, r)` |
| Activity | `requestAccess(Activity, Context, r, r)` | 存在，但会把 Activity 交给插件 UI 弹搜索界面，**服务里没有 Activity** |
| 心率回调 | `onNewHeartRateData(ts, flags, HeartRateData data)` | **没有 data 对象**，是 `(long, EnumSet<EventFlag>, int hr, long beatCount, BigDecimal eventTime, DataState)` |
| Page 4 回调 | 从回调里取 `rrFlag` | `IPage4AddtDataReceiver` 里**没有 rrFlag、没有当前事件时间**，只有 `manufacturerSpecificByte` 和 `previousHeartBeatEventTime` |
| 适配器缺失 | `NO_ANT_SUPPORTED_ADAPTER` | 实际常量是 `ADAPTER_NOT_DETECTED` |
| 释放 | 直接 `releaseAccess()` | 应 `PccReleaseHandle.close()`，否则通道会被占住 |

因此本实现的策略是：

- **订阅 `subscribePage4AddtDataEvent` 就等价于"设备在发 Page 4"**，不再依赖拿不到的 `rrFlag` 过滤
- 逐拍 RR 取自 `onNewHeartRateData` 的 `heartBeatEventTime`（BigDecimal → tick）与 `heartBeatCount`
- `DataState.INITIAL_VALUE` 的首帧直接丢弃（设备刚连上给的占位值，参与差分会产生巨大假 RR）

`SdkReflectionContractTest` 里 11 个断言锁死了上面全部签名 —— SDK 升级后反射会静默失效，
只有这些测试能第一时间抓出来。

### 前置条件（设备侧）

| 条件 | 说明 |
|---|---|
| ANT 硬件 | 手机内置 ANT 芯片（三星/LG/部分索尼老机型），或外接 ANT+ 适配器 |
| ANT Radio Service | 包名 `com.dsi.ant.service.socket`，设备内置 |
| ANT+ Plugin Service | 包名 `com.dsi.ant.plugins.antplus` |
| 蓝牙权限 | API 31+ 需 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，首次启动会申请 |

不带 ANT 硬件的手机（绝大多数国产新机）**物理上无法**收 ANT+ 信号，
这时 App 会明确提示而不是假装工作中。要验证 Page 4，得用支持 ANT+ 的机器或外接适配器。

## 3. 如何验证 Page 4

Page 4 是心率带在"逐拍模式"下发的数据帧，标志位 `RrFlag == DATA_SOURCE_PAGE_4`。
很多手表/App 走的是 Page 0（只有平均心率），**必须让胸带进入逐拍模式**。

### 3.1 确认设备真的在发 Page 4

1. 戴好 H303，电极区打湿
2. App 内点「连接心率带」，等待状态变成 **已连接**
3. 顶部状态下方会显示诊断信息；界面「已记录拍数」开始增长 → 说明收到有效 RR
4. 看 logcat：

```bash
adb logcat -s AntPlusManager:D SdkAntSource:D RRLogger:D
```

关键判据：
- 有 `page4Samples` 增长（内部统计）→ Page 4 帧确实在来
- `已记录拍数` 也增长 → 解析出的 RR 通过了丢拍检测和生理过滤
- 若只有 `otherPageSamples` 增长 → 胸带没进逐拍模式（换电池 / 重新贴合电极 / 用官方 App 激活一次）

### 3.2 常见"收不到 Page 4"的原因

| 现象 | 原因 | 处理 |
|---|---|---|
| 一直「搜索心率带中…」 | 胸带没在广播 / 电量低 | 换电池，电极打湿，贴近手机 |
| 连接成功但拍数不动 | 胸带只发 Page 0 | 部分固件需要先被其他 App/手表激活逐拍模式 |
| 拍数增长但丢弃很多 | 电极接触不良，RR 抖动超范围 | 重新佩戴，加导电胶 |
| 连接即报 `NO_ANT_SUPPORTED_ADAPTER` | 本机无 ANT 硬件 | 换设备或外接适配器 |

---

## 4. 如何导出到 Kubios

1. 结束会话（点「结束记录」）
2. 点「导出最近会话」，或「历史会话」里选一条
3. 文件写入 `Download/RRLogger/rr_yyyyMMdd_HHmm_<label>.txt`

文件内容严格是**每行一个整数毫秒**，没有表头、没有单位、没有逗号：

```
1001
998
1002
999
```

这正是 Kubios 的 *RR interval text file* 格式。

### Kubios 导入步骤

1. 打开 Kubios HRV（Standard / Premium / Scientific 均可）
2. `File → Open` 或直接拖入 txt
3. 导入向导里选 **RR interval data**，采样信息选 *Not available / Use RR intervals*
4. 确认时间轴解释正确后 Import
5. 左侧 *Analysis* 面板即可出时域（Mean RR、SDNN、RMSSD、pNN50）和频域结果

### 为什么这样写才对

Kubios 解析 txt 时按"一个数一行"读 RR 序列。任何表头、单位行、逗号分隔的 CSV、
或者用接收时间戳算出来的等间隔序列，都会让 Kubios 把它当成"等间隔采样信号"或直接报错。
所以导出器只做一件事：把 `RrRecord.rrMs` 按 `timestampMs` 升序逐行写出。

会话的标签、时长、平均心率都放在**文件名和数据库**里，不污染文件内容。

---

## 5. 核心实现约束（对照设计文档）

| 约束 | 实现位置 |
|---|---|
| RR 只能来自 `heartBeatEventTime` 差值 | `RrCalculator.eventTimeDiff` / `Page4RrParser.onSample` |
| 只认 Page 4，禁止跨页拼接 | `Page4RrParser.onSample` 首行 `if (!isPage4) return` |
| UI 线程不做 ANT 回调 | 回调只做计算，写库走 `RecordingService` 的 `Dispatchers.Default` 协程 |
| 每条有效 RR 立即插入，不攒批 | `RecordingService.startCollecting` 内 `repo.insertRr(...)` 逐条写 |
| ANT 不可用不崩溃 | `AntPlusManager.connect()` 前置 `availability.usable` 检查 + `ConnState.Error` |
| 前台服务权限完整 | Manifest：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_HEALTH`、`POST_NOTIFICATIONS` |
| 回绕单元测试 | `RrCalculatorWrapTest.doc example wrap gives 1036 ticks` |
| 重启恢复未结束会话 | `onCreate` / `onStartCommand` → `repo.findUnfinishedSession()` → `resumeSession(sameId)` |

### 两种协议的 droppedCount 含义不同（重要，别混着看）

设计文档 4.1 第 4 条写的是「`heartBeatCount` 跳变 > 1 则丢弃」，那是 ANT+ 的判据。

- **ANT+ 模式**：`droppedCount` = 丢拍数 + 超出生理范围数。两者都能确定。
- **BLE 模式**：`droppedCount` **只含超出生理范围**的样本。BLE 没有心跳计数器，
  无法判断是否丢拍，所以这个数字看起来会比 ANT+ 小——这是语义差异，不是 bug。

另外 BLE 模式下有一个「疑似伪影」计数（相邻 RR 变化 > 35%），
**只统计、不丢数据**。原因：文档 1.2 明确「不做自动伪影校正，仅做生理范围过滤」，
而真实的心率突变（起身、间歇冲刺起步）容易被误判成伪影，
擅自删掉会破坏 RR 序列的时间结构，反而让 Kubios 的时域/频域分析失真。
伪影校正交给 Kubios 自己做（它内置 artifact correction）。

### 两处对文档的修正（已实现，理由在此）

1. **`heartBeatCount` 是 8 位，不是 16 位。**
   文档写"跳变超过 1 就丢拍"，如果直接做整数相减，每 256 拍（255→0）会误丢一次，
   而 255 拍在 60bpm 下才 4 分钟，长期记录里会持续误杀。
   实现用 `RrCalculator.beatCountDelta` 做 8 位回绕归一化：
   `255 → 0` 的 delta 是 1，正常；`255 → 1` 的 delta 是 2，确实丢了 0 那一拍，丢弃。
   测试 `BeatDropTest.long run at 60bpm accumulates all beats` 覆盖了 600 拍跨两次翻转全通过。

2. **`rrMs = diff * 1000 / 1024` 用四舍五入而不是截断。**
   文档示例 1036 ticks 两种算法都得 1012，但截断在慢心率处会系统性偏小约 0.5ms
   （例如 1000 ticks：截断 976，四舍五入 977，真值 976.56）。
   长期 RR 序列的均值会被系统性拉低，影响 Kubios 的 Mean RR。
   实现见 `RrCalculator.ticksToMs`，测试 `ticks to ms rounding boundary` 明确锁定。

---

## 6. 构建与运行

```bash
# 环境
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17

cd RRLogger
./gradlew :app:assembleDebug          # 出 APK
./gradlew :app:testDebugUnitTest      # 跑单元测试
./gradlew :app:installDebug           # 装到设备
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

最低支持 API 26，target/compile API 34，Kotlin 1.9.24，AGP 8.5.2，Gradle 8.7。

---

## 7. 后台长时间记录的可靠性

- 前台服务类型 `health`，API 34 走 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_HEALTH)`
- API 34 以下额外持有 `PARTIAL_WAKE_LOCK`（12 小时上限），防止 CPU 休眠断流
- `START_STICKY` + Room 里的 `endTime IS NULL` 会话 → 进程被杀后自动续写同一个 `sessionId`
- 每条 RR 独立事务落库，最坏情况只丢最后半拍
- 每 20 拍把 `totalBeats / avgHr / droppedCount` 回写到 Session 表

电池优化白名单建议手动加一次：设置 → 应用 → R-R Logger → 电池 → 不受限制。
部分厂商（小米/华为/OPPO）还需在"自启动/后台运行"里单独放行。


---

## 8. BLE 模式验证（不需要 ANT 硬件）

1. 装 App，打开蓝牙（不需要配对）
2. 顶栏「协议」确认显示 **BLE**（默认就是）
3. 点「连接心率带」→ 状态变「搜索心率带中…」
4. 戴好 H303，电极区打湿 → 应在 30 秒内连上，状态变「已连接 · <设备名>」
5. 点「开始记录」→「已记录拍数」开始增长

logcat 观测：

```bash
adb logcat -s BleHeartRateSource:D HeartRateManager:D
```

关键日志：

```
startScan, preferred=null
found XX:XX:XX:XX:XX:XX name=H303 rssi=-60
onConnectionStateChange status=0 newState=2
notification enabled, waiting for RR data
```

看到最后一行就说明 `0x2A37` 通知已开，接下来每来一帧就会出 RR。

### BLE 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 一直「搜索心率带中…」 | 胸带没广播 / 被其他 App 占着 | 关掉 Kubios 等其他心率 App，胸带贴紧皮肤 |
| 连上但拍数不动 | 胸带只在心率 >0 时才发 RR | 确认电极接触良好 |
| 报「设备没有心率服务（0x180D）」 | 连到了别的 BLE 设备 | 关掉附近其他 BLE 设备后重试 |
| GATT 133 | Android 常见连接错误，多为设备端未就绪 | 等 2 秒重试，通常第二次就成功 |
| 连接超时 20s | 距离太远 / 电量低 | 手机贴近胸带，换 CR2032 |

### 关于 Kubios 的 ANT 实现

如果 Kubios 手机版在**没有 ANT HAL 的手机**上仍能显示 ANT 风格设备号（如 `39778-1`），
说明它自带了协议实现。本工程的 ANT 路径依赖系统 `com.dsi.ant.service.socket`，
在 `ANT HAL = Not Found` 的设备上无法工作——这类设备请直接用 BLE 模式，
数据精度与导出结果完全一致。


---


## 版本管理

见 [VERSIONS.md](VERSIONS.md)。核心规则：**迭代不覆盖，先复制带版本号的目录副本再改。**
历史版本存档在 `/root/RRLogger_versions/`。

---

