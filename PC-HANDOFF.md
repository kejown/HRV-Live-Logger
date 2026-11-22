# PC 端接手说明（PC-HANDOFF）

> **v6 提示（2026-11-22）**：本目录已完成 UI 设计文档的 **P0 + P2**——修复"第二段不能开始"、
> 顶栏/统计降噪、最近拍折叠、专注模式、记录期常亮、未连接可开始。单元测试 84 个 0 失败，
> lint 0 error / 9 warning。P1 视觉（主色/字体/区间条/波形光晕）与 Lap 数据模型**未做**。
> 详见 [CHANGES-v6.md](CHANGES-v6.md) 与 [VERSIONS.md](VERSIONS.md)。
> 下文描述的 v4 状态与设计不变量仍然有效。

这份文档是给**在 PC 上继续优化这个工程的人 / dsh 会话**看的。
手机端（aarch64 容器）已经交付并验收完毕，PC 端接手只需要看这一份。

- 工程：RRLogger —— 心率带 R-R 间期长程记录器（ANT+ / BLE 双协议）
- 版本：v4（UI 刷新版）
- 规模：Kotlin 4512 行（主代码 3066 / 测试 1446），XML 643 行，28 个 .kt
- 状态：70 个单元测试全绿，lint 0 error，APK 6.46 MB，5 项验收已通过

---

## 0. 先明确：这是安卓工程，PC 只是构建主机

**运行目标从头到尾只有一个：Android 手机。** PC 上不产出任何可运行的桌面程序。

```
PC（构建主机）                      Android 手机（唯一运行目标）
─────────────────                   ────────────────────────────
JDK 17 + Android SDK                minSdk 26 / targetSdk 34
   │                                   │
   ├─ ./gradlew assembleDebug ────────► app-debug.apk  ──adb install──►  装到手机跑
   ├─ ./gradlew testDebugUnitTest      单元测试在 PC 上跑（纯 JVM，不需要手机）
   └─ ./gradlew lintDebug              静态检查在 PC 上跑
```

换到 PC 上的**唯一目的**是：构建更快、能连 adb 看 logcat、能跑更完整的测试。
架构、依赖、SDK 版本、权限、数据库一律不动。

**因此下面这些做法一律不要引入**（会把这个工程从安卓方向带偏）：

| ❌ 不要做 | 原因 |
|---|---|
| 加 Kotlin Multiplatform / KMP 的 desktop 或 jvm target | 这是给手机记录心率的 App，没有桌面运行场景 |
| 换 Compose Multiplatform | 会连带换掉整套 UI 依赖，风险远大于收益 |
| 把 UI 改成 Compose / 换掉现有 View + ViewBinding | 与"优化"无关，属于重写；真要换必须走新版本目录 |
| 为了"能在 PC 上调试方便"而抽出纯 JVM 模块替换 Android 实现 | BLE/ANT+ 都要 Android Framework 的类，抽出去就成了另一个项目 |
| 引 `java.awt` / `javax.swing` / 桌面数据库 / 本地文件路径 | 手机上不存在这些东西，编译能过也会在真机上崩 |
| 用 `java.io.File` 直写 `Download/` 作为主路径 | Android 10+ 走 MediaStore 分支，现有代码已处理；别退回旧写法 |

判断标准只有一条：**改完之后还是不是那个能装到手机上、连着心率带记录 R-R 的 App。**
是 → 可以改。不是 → 方向错了，停下。

---

## 0.1 PC 上能验什么、必须上真机验什么

PC 的边界要清楚，不然容易误判"改好了"。

| 项 | PC 上可验 | 必须真机 |
|---|---|---|
| 纯逻辑（回绕/换算/过滤/单位判定/包解析） | ✅ 70 个单元测试覆盖 | — |
| 编译、资源打包、清单合并、lint | ✅ | — |
| UI 测试（Robolectric，x86-64 可用） | ✅ 有条件 | 视觉/触摸体验仍需真机 |
| APK 安装与启动 | ❌ | ✅ `installDebug` |
| BLE 扫描 / 连接 / GATT 通知 | ❌ 没有蓝牙硬件 | ✅ |
| ANT+ 连接（需芯片+HAL 的机型） | ❌ | ✅ |
| 前台服务后台存活 30 分钟 | ❌ | ✅ |
| 杀进程后数据恢复 | ❌ | ✅ |
| 导出文件被 Kubios 导入 | ❌ | ✅ |

> 记住：**单元测试全绿 ≠ 真机没问题。** 动过协议层或服务层的代码，
> 上表右侧四项必须重测一遍。

---

## 1. 三步接手

```bash
cd RRLogger
bash scripts/pc-setup.sh          # 自检 JDK17 / SDK / aapt2，并生成 local.properties
bash scripts/verify.sh            # 测试 + 构建 + lint，末尾打印摘要
```

单跑也行：

```bash
./gradlew :app:testDebugUnitTest   # 70 个单元测试
./gradlew :app:assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug           # 静态检查
./gradlew :app:installDebug        # 装到连着 adb 的手机上
bash scripts/deploy.sh             # 编译+安装+启动+跟日志，一条命令
```

> **和手机端最大的区别：PC 上不需要 aapt2 覆盖参数。**
> 手机端是 aarch64，Google 只发 x86-64 版 aapt2，所以那边启动了 qemu-user-static 做兼容层。
> PC（x86-64）上 AGP 自己从 Google Maven 拉 x86-64 二进制，一行参数都不用加。
> `BUILD.md` 里「ARM64 主机」那一节在 PC 上是**死代码**，别照着做。

---

## 2. 环境要求

| 项 | 版本 | 备注 |
|---|---|---|
| JDK | **17** | AGP 8.5 硬要求，21 会报错 |
| Android SDK | platform **34** + build-tools **34.0.0** | compileSdk/targetSdk = 34 |
| Gradle | 8.7 | 已内置 wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`），首次运行自动下载 |
| AGP / Kotlin / KSP | 8.5.2 / 1.9.x / 对应版本 | 见 `build.gradle.kts` 与 settings 的 plugin 版本 |
| 磁盘 | 首次构建约 2 GB（Gradle 缓存 + 依赖 + 构建产物） | |

`local.properties` 是机器相关的，包里给的是 `local.properties.example`，
`pc-setup.sh` 会自动按探测到的 SDK 路径生成真的那份。**不要跨机器复制这个文件。**

---

## 3. 目录地图

```
RRLogger/
├── PC-HANDOFF.md              ← 本文件
├── README.md                  ← 功能、ANT+ SDK 配置、Kubios 导出、Page 4 验证方法
├── BUILD.md                   ← 构建环境（含 ARM64/qemu 那套，PC 上忽略）
├── VERSIONS.md                ← 版本时间线 + 迭代纪律 + 踩坑记录
├── local.properties.example
├── scripts/
│   ├── pc-setup.sh            ← 环境自检 + 生成 local.properties
│   ├── verify.sh              ← test + build + lint 一条龙（PC 静态验收）
│   └── deploy.sh              ← 编译+安装到手机+启动+跟 logcat（真机联调）
├── gradle/wrapper/            ← 标准 wrapper（jar 已含，离线可用）
└── app/
    ├── libs/
    │   ├── antpluginlib_3-9-0.aar    ← ANT+ 官方 SDK，走反射调用，非编译期依赖
    │   └── README.txt                ← 换 SDK 版本的下载地址与注意事项
    └── src/
        ├── main/java/com/rrlogger/
        │   ├── RRLoggerApp.kt            (5)    Application
        │   ├── util/Logs.kt             (26)
        │   ├── util/TimeFormat.kt       (33)
        │   ├── data/RrRecord.kt         (40)   Room 实体
        │   ├── data/Session.kt          (46)
        │   ├── data/AppDatabase.kt      (44)   synchronous=FULL
        │   ├── data/Daos.kt             (84)
        │   ├── data/RrRepository.kt     (67)
        │   ├── ant/AntAvailability.kt   (58)   探测 ANT 服务/插件是否安装
        │   ├── ant/RrCalculator.kt     (166)  ★纯函数：回绕/换算/8bit计数/生理过滤
        │   ├── ant/SdkAntHeartRateSource.kt (373)  反射驱动官方 SDK 3.9.0
        │   ├── ble/BleRrParser.kt       (90)  ★纯函数：0x2A37 flags 解析
        │   ├── ble/BleBeatValidator.kt (103)  ★纯函数：BLE 无拍计数器，只丢越界
        │   ├── ble/RrUnitVerifier.kt   (119)  ★纯函数：tick vs ms 单位交叉验证
        │   ├── ble/BleHeartRateSource.kt (460) 扫描/连接/GATT 通知
        │   ├── source/HeartRateSource.kt (78)  协议无关接口（SourceKind/Listener）
        │   ├── source/HeartRateManager.kt (223)★唯一做 tick→ms 的地方
        │   ├── service/RecordingService.kt (545) 前台服务，逐拍入库
        │   ├── export/RrExporter.kt    (177)  Kubios 纯文本导出
        │   ├── ui/MainActivity.kt      (442)
        │   ├── ui/MainViewModel.kt     (233)
        │   └── ui/widget/RrSparklineView.kt (236) 零分配 onDraw
        ├── main/res/                  ← activity_main.xml 404 行 + 10 个 drawable/values
        └── test/java/com/rrlogger/    ← 70 个单元测试，6 个文件
            ├── RrCalculatorWrapTest.kt        (8)   16bit 回绕
            ├── PhysiologicalFilterTest.kt     (8)   300..2000ms 边界
            ├── BeatDropTest.kt               (11)  8bit 拍计数跳变
            ├── SdkReflectionContractTest.kt  (11)  锁死 SDK 3.9.0 反射签名
            ├── BleRrParserTest.kt            (11)  0x2A37 flags 组合
            ├── BleBeatValidatorTest.kt        (8)
            └── RrUnitVerifierTest.kt         (13)  单位判定收敛
```

★ = 纯函数 / 无 Android 依赖，改这里最安全，也有测试兜底。

---

## 4. 不可破坏的设计不变量

改代码前先读这 6 条。破坏任意一条都会静默产出**错的 R-R 数据**，而错的 R-R 数据在 Kubios 里看不出来。

1. **tick→ms 只在一处转换**
   两个协议源都输出 `rrTicks`（1/1024 秒），毫秒换算只允许发生在 `HeartRateManager`。
   在别处再转一次 = 数据差 2.34%，Kubios 的 HRV 指标全部偏移。

2. **16 位事件时间必须处理回绕**
   `curr >= prev → curr - prev`，否则 `curr + 65536 - prev`。
   漏掉会在每次 65.536 秒（以 1/1024s 计）处产出一个巨大的假 R-R。

3. **`heartBeatCount` 是 8 位，不是 16 位**
   原始设计文档写的是"跳变 > 1 即丢拍"，那是按 16 位写的。ANT+ Page 4 的 beat count 是 8 bit，
   按 16 位判会把每 256 拍当成丢拍。`RrCalculator.beatCountDelta` 已按 8 bit 归一化，别改回去。

4. **生理过滤边界是闭区间 300..2000 ms（含端点）**
   对应 30..200 bpm。边界值本身合法，测试里锁了 300 和 2000 两个端点。

5. **心率值 255 是无效哨兵**
   `sanitizeHeartRate` 必须拒绝 0 和 255，合法区间 20..240。
   曾因为漏了 255 导致 UI 上闪过一个 255 bpm 的假读数。

6. **逐拍立即入库，不做批量缓冲**
   `RecordingService` 每收到一拍就 `repo.insertRr()`。这是"杀进程不丢数据"验收项的
   唯一保障——缓冲一批再写，被杀时必然丢最后一批。想优化 I/O 先把这条否掉再谈。

---

## 5. 优化切入点（按"收益 / 风险"排序）

已经实测过现状，下面这些是真实存在的改进空间，不是凑数。

### 值得做，风险低

| # | 位置 | 现状 | 建议 | 风险 |
|---|---|---|---|---|
| 1 | `MainActivity.kt` 442 行 | 一个 Activity 里混了权限申请、UI 绑定、按钮回调、格式化 | 抽 `PermissionGate`、`HrPresenter`、`UiFormatter` 三个类出去 | 低，纯结构重排 |
| 2 | `RecordingService.kt` 545 行 | 服务里同时管生命周期、采集、统计、恢复、通知、错误态 | 抽 `SessionRecorder`（采集+统计）与 `ServiceNotification` | 低 |
| 3 | `BleHeartRateSource.kt` 460 行 | 扫描/连接/重连/通知/超时全在一个类 | 抽 `GattConnector`；重连退避目前是固定值，可换指数退避 | 中，涉及状态机 |
| 4 | `RrExporter.kt` 的 `body: String` | 整段拼成一个 String 再写（8 小时约 29k 行 ≈ 200 KB） | 改成 `BufferedWriter` 逐行写 + `listRrMs` 分页 | 低 |
| 5 | release 变体 | `isMinifyEnabled = false`，未上 R8 | 打开 minify + resource shrinking，APK 能小 30%+ | 中，需验证反射不被混淆（`-keep` 已在 proguard 里，但要实测） |
| 6 | 依赖 | `androidx.test:core` 等测试依赖注释里还留着 aarch64 的说明 | 迁到 `libs.versions.toml` 版本目录，注释按平台分节 | 低 |

### 值得评估，需要真机

| # | 位置 | 问题 | 说明 |
|---|---|---|---|
| 7 | `SdkAntHeartRateSource` | 每次回调都用 `method.invoke` 反射 | 可缓存 `Method` 到字段（已部分做）或上 `MethodHandle`。省的是微秒级，**但反射是 ANT 能否跑通的关键路径**，改动后必须跑 `SdkReflectionContractTest` + 真机复测 |
| 8 | `RrSparklineView` | 每拍 `invalidate()` 一次 | 60bpm 即 1Hz，不构成压力。若提高到 240Hz 采样设备（如 H10 高频模式）才需要降频 |
| 9 | BLE 采样率 | 未请求 MTU、未处理 `onCharacteristicChanged` 的两个重载差异（已兼容） | 心率带无大数据包，MTU 意义不大；**不要**为"低延迟"去开 BLE 5 的 2M PHY，部分国产带固件会掉连接 |
| 10 | `droppedCount` 语义 | ANT+ 含丢拍，BLE 只含越界，两者含义不同 | 已文档化，**没统一是有意的**。要统一得先决定 BLE 的丢拍怎么定义 |
| 11 | 历史列表 | `observeSessions()` 全量返回 | 会话数上千后需要 Paging 3；当前规模无必要 |

### 明确不要做

- ❌ 加 Hilt / Koin：现在 3 个单例，靠构造函数传就够，DI 框架只会增加编译时间
- ❌ 把 Room 换成 DataStore / SQLite 裸写：Room 的 `synchronous=FULL` 是数据不丢的前提
- ❌ 给 R-R 加"平滑/插值"：这是分析层的事，采集层插值等于伪造数据，Kubios 明确不要
- ❌ 合并 `SharedFlow` 与 `Channel` 双通道：一个给 UI（可丢），一个给服务（不可丢），语义不同

---

## 6. 已知坑

1. **ANT+ 在多数国产机上根本跑不起来**
   需要 ANT 芯片固件 + 厂商 HAL。用户态装 ANT Radio Service 也没用（JNI 会找不到 HAL）。
   判断方法、以及那台测试机（Xiaomi 23113RKC6C / Android 16）的实测结果都在 `VERSIONS.md`。
   所以 BLE 才是实际可用路径，两份实现都保留着。

2. **antpluginlib 3.9.0 的 API 和网上老示例差异很大**
   `requestAccess` 没有 3 参数重载；心率回调是裸参数（没有 Data 对象）；适配器枚举名也不同。
   11 个 `SdkReflectionContractTest` 把这些签名锁死，换 SDK 版本时会立刻报错——**这是特性，不是麻烦**。

3. **部分国产带头固件把 R-R 当毫秒发**
   SIG 规范是 1/1024 秒，实测有的固件直接给毫秒，差 2.34%。
   `RrUnitVerifier` 用广播心率做交叉验证自动判定，12 个样本后收敛，比值 > 3 倍才裁决。

4. **Robolectric 在 aarch64 上跑不了**（native runtime 缺失），所以 UI 靠 Pillow 脚本出预览图。
   PC 上是 x86-64，**Robolectric 可以用**——想加 UI 测试的话现在有条件了。

5. **`gradlew` 是标准 wrapper 了**
   手机端那份是手写的 shell 脚本（因为 wrapper jar 缺失）。PC 包里已换成标准 wrapper，
   Windows 下用 `gradlew.bat`。

---

## 7. 版本纪律（硬规则）

**不要原地覆盖。** 每次迭代先复制：

```bash
cp -r RRLogger RRLogger_v5_<主题>
cd RRLogger_v5_<主题>
# 改这里，不动上一版
```

快照命名 `v5_<主题>_src.tar.gz` + `v5_<主题>.apk`，交付文件名带版本号，不覆盖旧的。

这条规则的来历：v1/v2/v3 三个版本因为原地覆盖已经全部丢失（v1 纯 ANT+ 27 测试、
v2 双协议 57 测试、v3 RR 单位判定 70 测试），只剩 v4。见 `VERSIONS.md`。

PC 端建议直接 `git init` + 首次提交，之后每次迭代一个 commit，能 diff 能回滚，
比 tar 快照省事得多。（本包未包含 `.git`，也不替你做提交。）

---

## 8. PC ↔ 真机联调（adb）

PC 上最大的实际收益就在这里：改一行就能装一次、能实时看日志。

### 8.1 装包

```bash
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"

adb devices                      # 确认设备已授权（unauthorized 就在手机上点允许）
./gradlew :app:installDebug      # 编译 + 安装一步到位
adb shell am start -n com.rrlogger/.ui.MainActivity
```

也可以用 `bash scripts/deploy.sh`（包内已提供，见下）。

### 8.2 看日志

本工程所有日志统一走 `Logs` 对象，TAG 固定为 **`RRLogger`**：

```bash
adb logcat -s RRLogger            # 只看本 App
adb logcat -s RRLogger AndroidRuntime   # 顺带看崩溃堆栈
adb logcat -c                     # 测之前先清缓冲，免得混进旧日志
```

排查协议问题时按关键字过滤：

```bash
adb logcat -s RRLogger | grep -iE "page4|rr|beat|drop|unit"
```

### 8.3 关键验证场景的跑法

| 要验的 | 怎么做 |
|---|---|
| BLE 能连 | 手机开蓝牙 → App 里点「连接」→ 看日志有无 GATT 连接与 0x2A37 通知 |
| 心率实时刷新 | 戴好带子，UI 心率数字应每秒跳；日志每拍一条 RR |
| 后台 30 分钟不断 | 开始记录后退到桌面，`adb shell dumpsys activity services com.rrlogger` 看服务是否存活；30 分钟后回来看拍数 |
| 杀进程不丢数据 | `adb shell am force-stop com.rrlogger` → 重开 App → 会话应在，拍数与杀之前一致 |
| Kubios 能导入 | 导出后在手机 `Download/RRLogger/` 取 txt，电脑上用 Kubios 打开，每行一个 RR 毫秒值 |

### 8.4 注意

- **BLE 心率带同一时刻通常只能被一个中心设备连接**：如果 Kubios、Zepp、其他 App 还连着，
  本 App 会收到 GATT 133 或连不上。测之前先断开其它 App。
- 部分国产 ROM 需要在系统设置里给本 App 开「自启动」「后台弹出」「省电无限制」，
  否则前台服务仍可能被拦。这是厂商策略，不是代码问题。
- Android 13+ 首次记录要授权 `POST_NOTIFICATIONS`，否则前台服务通知不显示（服务仍在跑）。
- Android 12+ 需要 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；
  12 以下扫 BLE 需要定位权限且**系统定位开关要打开**。

---

## 9. 接手后的第一轮验收

分两级。**第一级在 PC 上跑，第二级必须上手机。**

### 第一级：PC 静态验收（每次改完都跑）

```bash
bash scripts/verify.sh
```

判定标准：

- 单元测试 **70 个，0 失败**
- `lint` 无 **error**（warning 允许，当前 16 条均为排版/overdraw 类）
- APK 正常产出（`app/build/outputs/apk/debug/app-debug.apk`）

### 第二级：安卓真机验收（动过协议层 / 服务层 / 权限 / 清单就跑）

PC 上这四项**一项都测不了**，只能上手机：

```bash
./gradlew :app:installDebug && adb logcat -c && adb logcat -s RRLogger
```

1. BLE 心率带能连上，UI 心率每秒刷新
2. 退到后台 **30 分钟**不断，回来后拍数连续
3. `am force-stop` 杀进程，重开 App 数据完整恢复
4. 导出 txt 能在 Kubios 里导入

四项都过，才算这一轮真的没问题。只过了第一级，只说明"没编译错、纯逻辑没写错"。
