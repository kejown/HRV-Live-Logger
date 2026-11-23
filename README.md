# HRV Live Logger

一个用于实时心率和 RR 间期监测的安卓 App，专为软件生态不完善的心率带设计，支持直接导出至 Kubios HRV。

> 🚨 **普通用户 / 跑步党直接看这里（开箱即用）**
> 1. 在仓库根目录（或右侧 Releases 页面）下载最新的 `.apk` 安装包。
> 2. 安装到你的安卓手机上（最低支持 Android 8.0）。
> 3. 打开手机蓝牙，戴好心率带（建议电极区打湿），打开 App 直接点「连接心率带」即可。
> 
> *💡 提示：不用折腾底层协议，App 默认使用 BLE 模式，兼容市面上 99% 的心率带。数据会存在本地，随时可以导出给 Kubios。*

<!-- 建议把你这张图放进去，效果最好 -->
<!-- ![主界面截图](assets/screenshot.png) -->

---

## ✨ 核心功能

- **双协议支持**：同时支持 BLE（蓝牙低功耗）和 ANT+。App 内可运行时切换，默认使用 BLE。
- **纯净 RR 记录**：逐拍记录 R-R 间期，绝不擅自修改数据（不自动删伪影），确保 Kubios 分析准确。
- **格式直出**：一键导出为 Kubios HRV 可直接读取的纯文本格式。
- **后台保活**：前台服务 + Room 数据库，进程被杀也能续写，适合夜间睡眠或长跑记录。
- **实时 HRV 指标**：主界面显示最近 60 秒的 RMSSD / SDNN / pNN50；停止记录后给出整段会话摘要，历史会话可回看。

---

## 🫀 HRV 指标（v7，非医疗建议）

- **实时卡**：主界面心率卡下方，显示最近 **60 秒**滑窗的 `RMSSD` / `SDNN` / `pNN50`，右上角是样本数；不足 20 拍时只显示"样本不足"。
- **会话摘要**：点「结束记录」后弹一次，包含有效拍数、平均心率、Mean RR、SDNN、RMSSD、pNN50、SD1/SD2 与疑似伪影数。
- **历史详情**：在「历史」里选一条会话 → 「查看 HRV 详情」，可随时重新计算。
- **口径**：时域 + Poincaré；SDNN 为样本标准差（n−1），pNN50 用严格大于 50ms。**不做伪影校正、不改写入库的 RR**，导出给 Kubios 的仍是原始数据。
- **限制**：没有频域（LF/HF）；短窗或伪影多时 RMSSD 会偏高。个人基线趋势是下一阶段的事。

---

## 📖 如何导入 Kubios HRV

1. 在 App 内结束会话（点「结束记录」）。
2. 点「导出最近会话」，或「历史会话」里选一条。
3. 文件将写入 `Download/RRLogger/rr_yyyyMMdd_HHmm_<label>.txt`。
4. 打开 Kubios HRV（Standard / Premium / Scientific 均可），`File → Open` 拖入该 txt。
5. 导入向导里选 **RR interval data**，采样信息选 *Not available / Use RR intervals* 即可。

*为什么这样写才对？Kubios 解析 txt 时按"一个数一行"读 RR 序列。任何表头、单位、逗号分隔的 CSV 都会报错。本 App 只做一件事：把 `RrRecord.rrMs` 按 `timestampMs` 升序逐行写出。*

---

## 🛠️ 开发者与高阶玩法（点击展开）

<details>
<summary><b>点击查看：核心架构与两种协议对比（极客向）</b></summary>

### 0. 支持两种协议

| | ANT+ | BLE |
|---|---|---|
| 服务发现 | ANT Profile 120 + Page 4 | GATT 心率服务 `0x180D` / 特征 `0x2A37` |
| 数据单位 | heartBeatEventTime tick（1/1024 s） | RR-Interval uint16（1/1024 s）**同源** |
| 系统依赖 | 必须有 `com.dsi.ant.service.socket` + ANT 硬件 | Android 原生 BLE，无额外依赖 |
| 丢拍检测 | 有 `heartBeatCount`，能**确定**丢了几拍 | **没有计数器**，只能做生理范围过滤 |
| 设备覆盖 | 少数带 ANT 芯片的机型 / 外接适配器 | 几乎所有 Android 手机 |

两条路最终喂给 Room 的 `rrMs` 完全等价，导出格式一致，Kubios 无差别导入。
App 内「协议」按钮可运行时切换，默认 BLE。

**为什么默认 BLE**：ANT 需要系统级 ANT HAL，这个层由芯片固件与厂商决定，用户态装不了。多数国产新机不搭载 `ANT HAL`（显示为 `Not Found`），此时 ANT 路径物理上不可用，而 BLE 一定能用。

### 1. 工程结构

```text
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
├── service/
│   └── RecordingService.kt       前台服务：持有 ANT 连接，逐条写库，维护会话统计
├── export/
│   └── RrExporter.kt             导出 Kubios 兼容 txt 到公共 Downloads
├── ui/                           MainActivity + MainViewModel
└── util/                         TimeFormat / Logs
```

单元测试：app/src/test/java/com/rrlogger/

· RrCalculatorWrapTest — 回绕计算（含文档要求的 65000→500 = 1036）
· PhysiologicalFilterTest — 300/2000ms 边界内/外
· BeatDropTest — 丢拍检测、beatCount 8 位翻转、reset 语义

2. 关于丢拍与数据清洗（非常关键）

设计文档 4.1 第 4 条写的是「heartBeatCount 跳变 > 1 则丢弃」，那是 ANT+ 的判据。

· ANT+ 模式：droppedCount = 丢拍数 + 超出生理范围数。两者都能确定。
· BLE 模式：droppedCount 只含超出生理范围的样本。BLE 没有心跳计数器，无法判断是否丢拍。

另外，BLE 模式下有一个「疑似伪影」计数（相邻 RR 变化 > 35%），只统计、不丢数据。原因：真实的心率突变容易被误判成伪影，擅自删掉会破坏 RR 序列的时间结构，反而让 Kubios 分析失真。伪影校正交给 Kubios 自己做。

3. 两处对文档的修正（已实现）

1. heartBeatCount 是 8 位，不是 16 位。 实现用 RrCalculator.beatCountDelta 做 8 位回绕归一化（255 → 0 的 delta 是 1）。测试覆盖了 600 拍跨两次翻转全通过。
2. rrMs = diff * 1000 / 1024 用四舍五入而不是截断。 截断在慢心率处会系统性偏小约 0.5ms，长期 RR 序列的均值会被拉低，影响 Kubios 的 Mean RR。

</details>

<details>
<summary><b>点击查看：如何配置 ANT+ SDK 依赖（重要）</b></summary>

官方 ANT+ SDK 不发布到任何公开 Maven 仓库。所以工程采用本地 AAR 的方式：
app/libs/antpluginlib_3-9-0.aar ← 本工程已内置这一份。

在 app/build.gradle.kts 里配置：

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

下载地址（2026 年现状）

内容已迁移到 Garmin 开发者平台，真实直链是：
https://developer.garmin.com/downloads/ant/Android_API.zip
解包后取出 AntPlus_Android_SDK_v.P.B7.zip 里的 antpluginlib_3-9-0.aar。

一行命令搞定：

```bash
curl -LO https://developer.garmin.com/downloads/ant/Android_API.zip
unzip -o Android_API.zip 'Android_API/AntPlus_Android_SDK_v.P.B7.zip'
unzip -o Android_API/AntPlus_Android_SDK_v.P.B7.zip -d antplus
cp 'antplus/ANT+_Android_SDK/API/antpluginlib_3-9-0.aar' app/libs/
```

没放 AAR 会怎样：App 依然能编译、能安装、能启动，只是顶栏出现红色横幅，且「开始记录」保持禁用（不会崩）。

为什么用反射，以及踩过的坑

反射是为了让工程在没放 AAR 时也能编译。但 ANT+ SDK 3.9.0 的 API 和网上流传的老示例差别很大：

项目 常见的错误写法 3.9.0 的真实情况
连接入口 requestAccess(Context, r, r) 三参数 不存在三参数版。可用的是 (Context, int deviceNumber, int proximity, r, r)
心率回调 onNewHeartRateData(ts, flags, HeartRateData data) 没有 data 对象，是 (long, EnumSet<EventFlag>, int hr, long beatCount, BigDecimal eventTime, DataState)
Page 4 回调 从回调里取 rrFlag IPage4AddtDataReceiver 里没有 rrFlag，只有 manufacturerSpecificByte 和 previousHeartBeatEventTime
适配器缺失 NO_ANT_SUPPORTED_ADAPTER 实际常量是 ADAPTER_NOT_DETECTED

因此本实现策略是：

· 订阅 subscribePage4AddtDataEvent 就等价于"设备在发 Page 4"。
· 逐拍 RR 取自 onNewHeartRateData 的 heartBeatEventTime。
· DataState.INITIAL_VALUE 的首帧直接丢弃（设备刚连上给的占位值）。

SdkReflectionContractTest 里 11 个断言锁死了上面全部签名 —— SDK 升级后反射会静默失效，只有这些测试能第一时间抓出来。

</details>

<details>
<summary><b>点击查看：构建与运行</b></summary>

```bash
# 环境
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17

cd HRV-Live-Logger
./gradlew :app:assembleDebug          # 出 APK
./gradlew :app:testDebugUnitTest      # 跑单元测试
./gradlew :app:installDebug           # 装到设备
```

产物：app/build/outputs/apk/debug/app-debug.apk
最低支持 API 26，target/compile API 34，Kotlin 1.9.24，AGP 8.5.2，Gradle 8.7。

后台长时间记录的可靠性

· 前台服务类型 health，API 34 走 ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_HEALTH)。
· API 34 以下额外持有 PARTIAL_WAKE_LOCK（12 小时上限），防止 CPU 休眠断流。
· START_STICKY + Room 里的 endTime IS NULL 会话 → 进程被杀后自动续写同一个 sessionId。
· 电池优化白名单建议手动加一次：设置 → 应用 → HRV Live Logger → 电池 → 不受限制。

</details>

<details>
<summary><b>点击查看：日志调试与连接排错</b></summary>

BLE 模式验证

```bash
adb logcat -s BleHeartRateSource:D HeartRateManager:D
```

看到 notification enabled, waiting for RR data 说明 0x2A37 通知已开，接下来每来一帧就会出 RR。

BLE 常见问题：

现象 原因 处理
一直「搜索心率带中…」 胸带没广播 / 被其他 App 占着 关掉其他心率 App，胸带贴紧皮肤
连上但拍数不动 胸带只在心率 >0 时才发 RR 确认电极接触良好
报「设备没有心率服务」 连到了别的 BLE 设备 关掉附近其他 BLE 设备后重试
GATT 133 Android 常见连接错误 多为设备端未就绪，等 2 秒重试即可
连接超时 20s 距离太远 / 电量低 手机贴近胸带，换 CR2032

ANT+ 模式验证

```bash
adb logcat -s AntPlusManager:D SdkAntSource:D RRLogger:D
```

关键判据：有 page4Samples 增长 → Page 4 帧确实在来；已记录拍数 增长 → 解析出的 RR 通过过滤。

常见"收不到 Page 4"的原因：

· 一直「搜索心率带中…」：胸带没广播 / 电量低。
· 拍数增长但丢弃很多：电极接触不良，RR 抖动超范围。
· 连接即报 ADAPTER_NOT_DETECTED：本机无 ANT 硬件，换设备或外接适配器。

关于 Kubios 手机版的 ANT 实现

如果 Kubios 手机版在没有 ANT HAL 的手机上仍能显示 ANT 风格设备号，说明它自带了协议实现。本工程的 ANT 路径依赖系统 com.dsi.ant.service.socket，在 ANT HAL = Not Found 的设备上无法工作——这类设备请直接用 BLE 模式，数据精度与导出结果完全一致。

</details>

---

📦 版本管理

见 VERSIONS.md。核心规则：迭代不覆盖，先复制带版本号的目录副本再改。 历史版本存档在 /root/HRVLiveLogger_versions/。

---

⚠️ 免责声明

1. 非医疗器械：本软件（HRV Live Logger）仅作为开源的数据记录与分析工具，不构成任何医疗建议，也不是经认证的医疗器械。
2. 数据仅供参考：通过蓝牙（BLE）或 ANT+ 接收到的数据，其准确性完全取决于您使用的心率带硬件。软件对数据的完整性、准确性及连续性不做任何保证。
3. 风险自担：用户在使用本软件进行运动监测或健康管理时，应自行判断身体状况。因参考本软件数据而导致的任何健康问题、训练损伤或数据丢失，开发者不承担任何直接或间接责任。
4. 数据隐私：本软件采用本地存储机制，不收集、不上传您的个人数据（除非您主动导出）。请妥善保管您导出的 RR 文本文件。

```
