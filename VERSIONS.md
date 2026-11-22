# 版本与迭代规则

## 硬规则（YG 要求，必须遵守）

**任何迭代不要覆盖式重写。** 流程固定为三步：

```bash
# 1) 新版本用带版本号的目录副本，不碰旧版本
cp -r /root/RRLogger /root/RRLogger_v5_<主题>
cd /root/RRLogger_v5_<主题>
# ...在这个副本上改...

# 2) 改完打快照
tar czf /root/RRLogger_versions/v5_<主题>_src.tar.gz -C /root/RRLogger_v5_<主题> \
    --exclude=.gradle --exclude=build --exclude=app/build --exclude=local.properties .
cp /root/RRLogger_v5_<主题>/app/build/outputs/apk/debug/app-debug.apk \
   /root/RRLogger_versions/v5_<主题>.apk

# 3) 交付文件名带版本号，不覆盖 Download 里的旧产物
#    RRLogger-v5-debug.apk  而不是  RRLogger-debug.apk
```

原因：v1–v3 因为原地覆盖式修改已经丢失，无法回滚对比，
用户没法退回到"上次确认能用的版本"。

---

## 版本时间线

| 版本 | 内容 | 测试数 | 状态 |
|---|---|---|---|
| v1 | 纯 ANT+（反射驱动 antpluginlib 3.9.0），Page 4 逐拍，Room，Kubios 导出 | 27 | **已丢失**（被覆盖） |
| v2 | 加 BLE 双协议（0x180D / 0x2A37），运行时切换 | 57 | **已丢失**（被覆盖） |
| v3 | 加 RR 单位自动判定（tick / 毫秒）+ 连接优先级优化 | 70 | **已丢失**（被覆盖） |
| **v4** | 新 UI：大心率主卡、RR 实时曲线、数据质量提示、按钮分层 | 70 | 已存档，是 v5 的基线 |
| **v5** | PC 静态优化：导出改流式分页、tick→ms 取整修正、质量分级抽纯函数、版本目录、通知拆分 | 79 | 已存档（`main` 分支） |
| **v6** | P0+P2：修复第二段无法开始、顶栏/统计降噪、最近拍折叠、专注模式、常亮、未连接可开始 | 84 | **当前**（分支 `v6-ui-p0p2`，待真机视觉验收） |

> v1–v3 的丢失是覆盖式迭代的直接后果。从 v4 起按上面的流程留档。

### v4 存档位置

```
/root/RRLogger_versions/
├── v4_ui_refresh.apk           6.46 MB，可直接装
└── v4_ui_refresh_src.tar.gz    完整源码
```

---

## v4 改了什么（相对 v3）

**布局重做**（`res/layout/activity_main.xml` 全重写）

- 心率从"和别的信息一样大"变成唯一主视觉：104sp 细体 + 主色 + 独立带辉光的深色卡
- 其余信息全部降级到 12–13sp
- 7 个按钮平铺拆成两级：主操作（开始/结束/打点）+ 次级文字按钮（连接/导出/历史/重检）

**新增 `RrSparklineView`**（`ui/widget/RrSparklineView.kt`）

- RR 实时曲线，32 拍窗口，二次贝塞尔平滑
- 颜色跟随心率区间：<60 冷蓝 / 60–100 主红 / 100–140 橙 / >140 亮红
- 平均线虚线 + 最新点实心圆
- `onDraw` 零分配（Path 复用、画笔预分配）

**心跳脉冲动画**

心率每次刷新触发 85ms 的 1.055 倍缩放回弹。

**数据质量卡片**

把裸的 `droppedCount` 换算成丢弃率并给分级判断：
- <2% 优秀（绿）/ <5% 良好（绿）/ <15% 少量异常（黄）/ ≥15% 异常较多（红）

**其它**

- 最近 5 拍 → 10 拍
- 空状态心率占位符 `--` → `—`（超大字号下更干净）
- 记录中时按钮状态反转：开始变灰、结束变红色描边
- 清掉 5 个未引用的字符串资源
- 修 lint: `DrawAllocation`（原来 onDraw 里每帧 `new Paint()`）

**新增 `ui_preview.py`**

Robolectric 的 native 渲染只支持 x86_64，在 aarch64 上会 `AssertionError`。
改用 Pillow 按 dp×3 逐像素渲染布局预览图，字体用系统 Noto Sans CJK。
以后改布局可以先出图自检，不用每次装机看。

---

## 排查用的历史背景（避免重复踩坑）

### ANT+ 在多数国产机上不可用

ANT 需要芯片固件 + 厂商 HAL（`ANT HAL`），这层用户态装不了。
小米/Redmi 大部分型号 `Built-in ANT: No`、`ANT HAL: Not Found`，
装 `Android_ANTRadioService_*.apk` 会正常启动但永远搜不到设备。
**这类设备只能用 BLE 模式。**

判断方法：装 ANT SupportChecker 看 `ANT Capable` 是否为 ✓。

### ANT+ SDK 的真实签名（与网上老示例差异很大）

`antpluginlib_3-9-0.aar`：
- `requestAccess` 没有 3 参数重载，可用的是 `(Context, int deviceNumber, int proximity, r, r)`
- `onNewHeartRateData(long, EnumSet, int hr, long beatCount, BigDecimal eventTime, DataState)` —— 没有 Data 对象
- `IPage4AddtDataReceiver` 里没有 rrFlag、没有当前事件时间
- 适配器缺失枚举是 `ADAPTER_NOT_DETECTED`，不是 `NO_ANT_SUPPORTED_ADAPTER`
- 释放应调 `PccReleaseHandle.close()`

这些全部由 `SdkReflectionContractTest` 的 11 个断言锁死。

### BLE 的 RR 单位可能是毫秒

Bluetooth SIG 规定 1/1024 秒，但部分国产固件发毫秒。差 2.34%。
用「RR 反推心率 vs 广播心率」交叉验证，累积 12 帧自动判定。
判定结果显示在界面上（`RR 单位：TICK (n=42 ...)`）。
这大概率是"Kubios 看起来更准"的原因之一。

---

## PC 端优化包（v4_pc，衍生包不改代码）

2026-11-22 做出，用途：把工程搬到 PC 上继续优化。**它只是 v4 的重新打包，代码零改动。**

```
/root/RRLogger_versions/v4_pc_package_src.tar.gz   5.5 MB
/root/RRLogger_versions/v4_pc_package.zip          5.6 MB   同上，Windows 友好
```

包内结构：

```
RRLogger_pc_v4/
├── README-FIRST.txt      解压先看这个
├── SHA256SUMS.txt        61 个文件的校验和
├── prebuilt/
│   ├── v4_ui_refresh.apk 已验收产物
│   └── VERSIONS.md       本文件
└── RRLogger/             工程本体（858 KB）
    ├── PC-HANDOFF.md     ★ 接手必读
    ├── local.properties.example
    └── scripts/
        ├── pc-setup.sh   环境自检 + 生成 local.properties
        └── verify.sh     test + build + lint，打印 PASS/FAIL
```

相对 v4 源码的差异（只有打包层）：

| 改动 | 原因 |
|---|---|
| `gradlew` 换成标准 wrapper（含 `gradle-wrapper.jar`、`gradlew.bat`） | 原先是手写 shell 脚本，因为容器里缺 wrapper jar。PC 上需要真正跨平台的 wrapper |
| 新增 `PC-HANDOFF.md` | 交接文档：目录地图、6 条设计不变量、优化切入点、已知坑 |
| 新增 `scripts/pc-setup.sh`、`scripts/verify.sh` | 环境探测与一条龙验证 |
| 新增 `local.properties.example` | 原 `local.properties` 写死了容器路径，不能带出去 |
| 去掉 `local.properties`、`probe.txt`、`.gradle/`、`build/`、`app/build/` | 机器相关 / 构建产物 |
| `README.md` 追加「在 PC 上继续开发」一节 | 指路到 PC-HANDOFF.md |

**PC 上不需要 aapt2 覆盖参数**：`BUILD.md` 里 ARM64 + qemu-user-static 那一节只适用于 aarch64 容器。
PC 是 x86-64，AGP 自己拉 x86-64 二进制，一行参数都不加。

打包后做过独立验证（解压到全新目录，脱离原工程）：

```
bash scripts/pc-setup.sh   → 环境 [OK] ×5
sha256sum -c SHA256SUMS.txt → 61/61 OK
bash scripts/verify.sh     → 70 测试 0 失败 / lint 0 error 16 warning / APK 6.1 MB / PASS
```

### v4_pc r2（定位修正，代码仍零改动）

首版打包文档把"在 PC 上优化"写得像要跨平台，容易读成要做桌面版。**运行目标自始至终只有
Android 手机，PC 只是构建主机。** r2 补上这层定位，并划清验证边界：

- 新增 `PC-HANDOFF.md` 第 0 节：PC↔安卓分工图 + **6 条禁止引入的做法**
  （KMP desktop target、Compose Multiplatform、换 Compose、抽纯 JVM 模块替代 Android 实现、
  引 AWT/Swing、退回 `java.io.File` 直写 Download）
- 新增第 0.1 节：PC 上可验 / 必须真机验的对照表
- 新增第 8 节：adb 真机联调（installDebug、logcat TAG 过滤、五个关键场景的跑法、厂商 ROM 注意）
- 验收拆成两级：第一级 PC 静态验收（70 测试 + lint + APK），第二级安卓真机四项
- 新增 `scripts/deploy.sh`：编译 + 安装 + 启动 + 跟 logcat
- `README.md`、`README-FIRST.txt` 同步标注"运行目标只有安卓"

产物：

```
/root/RRLogger_versions/v4_pc_package_r2_src.tar.gz
/root/RRLogger_versions/v4_pc_package_r2.zip
```

---

## v5（PC 静态优化，2026-11-22）

**基线**：`RRLogger_pc_v4_r2/RRLogger`（v4 源码，代码零改动）。
**工作目录**：`RRLogger_v5_pc_opt/`，从 v4 复制后修改，v4 目录未动。
**PC 验收**：79 个单元测试 0 失败 / lint 0 error、16 warning / `app-debug.apk` 6.29 MB。

改动只碰纯逻辑、导出、构建配置与结构拆分，**未改**协议层、服务采集逻辑、数据库 schema、
权限与清单，也没有引入任何桌面/跨平台目标。

| # | 改动 | 文件 | 为什么 |
|---|---|---|---|
| 1 | 导出改成分页读取 + `BufferedWriter` 逐行写 | `export/RrExporter.kt`、`data/Daos.kt`、`data/RrRepository.kt` | v4 先拼一个完整 String 再整体写出；8 小时记录约 29k 行，分页后峰值内存与记录时长无关。失败时删除 MediaStore 的 pending 残件 |
| 2 | `ticksToMs` 从向上取整改为四舍五入 | `ant/RrCalculator.kt` | README 明确写的是"四舍五入而不是截断"，v4 实现是 ceiling，每个非整数毫秒多 +1ms（长期均值偏大约 0.5ms）。新增两条测试锁死取整语义与全量误差上限（≤0.5ms） |
| 3 | 数据质量分级抽成纯函数 | 新增 `ui/QualityAdvisor.kt`、`QualityAdvisorTest.kt`；`ui/MainActivity.kt` 只做文案/配色 | 阈值（2%/5%/15%、最少 20 样本）原本藏在 Activity 里无法单测，现在 7 条边界测试覆盖 |
| 4 | 依赖版本集中到 Version Catalog | 新增 `gradle/libs.versions.toml`；`build.gradle.kts`、`app/build.gradle.kts` | 版本号原先散落在两个构建文件里；版本号本身一个都没改 |
| 5 | 通知构建/更新/前台启动拆出服务 | 新增 `service/ServiceNotification.kt`；`RecordingService.kt` 从 545 行降到约 440 行 | 服务不再同时管通知通道、PendingIntent、文案与前台降级；通知文案与 v4 完全一致 |

测试数从 70 → 79：`RrCalculatorWrapTest` +2（取整语义、全量误差上限），
`QualityAdvisorTest` +7（样本量下限与 2%/5%/15% 边界两侧）。

### 本机 PC 构建注意（Windows）

这台机器的 `C:\Users\<user>` 目录对 `java.exe` 写入受限（PowerShell 可写、JVM 报
`AccessDeniedException`），因此需要把 JVM 临时目录指到工作区：

```powershell
$env:JAVA_HOME   = "E:\dsh workspace\.tools\jdk17\jdk-17.0.21.1+2"
$env:ANDROID_HOME = "E:\dsh workspace\.tools\android-sdk"
$env:ANDROID_USER_HOME = "E:\dsh workspace\.tools\android-home"   # 调试签名与 analytics
$env:GRADLE_USER_HOME  = "E:\dsh workspace\.tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="E:\dsh workspace\.tools\tmp"'
```

`ANDROID_USER_HOME` 决定 debug keystore 的位置（否则 AGP 会去写
`C:\Users\<user>\.android\debug.keystore` 并失败）。这些是**机器相关**的绕行，
不属于工程改动，换机器时按常规 `pc-setup.sh` 即可。

### v5 明确没有做

| 项 | 原因 |
|---|---|
| 打开 R8 / resource shrinking | 反射驱动 ANT+ SDK 是关键路径，必须真机验证混淆后的反射调用，PC 上只能证明能编译 |
| BLE 重连指数退避 | 涉及连接状态机，必须真机测断连/重连 |
| `MainActivity` 权限/绑定/回调三分 | 纯结构重排但无测试兜底，本轮先做有测试覆盖的质量分级抽取；建议真机在手时再做 |
| Room `synchronous=FULL`、逐拍入库、300..2000ms 边界 | 设计不变量，不动 |

### 仍需真机验收（第二级）

1. BLE 心率带能连、UI 心率每秒刷新；
2. 退后台 30 分钟不断、拍数连续；
3. `am force-stop` 后重开数据完整恢复；
4. 导出 txt 在 Kubios 导入成功，且每行仍是单个整数毫秒。

导出接口这次从 `listRrMs` 改成 `pageRrMs`，**第 4 项必须重测**——分页顺序与 v4 的
`ORDER BY timestampMs ASC, id ASC` 完全一致，但只有真机能确认文件内容没有被截断或重复。

---

## v6（P0 + P2，2026-11-22）

按 UI 设计文档执行 **P0（结构降噪/可用性）** 与 **P2（专注模式/常亮/未连接可开始）**，
**P1 视觉升级与 Lap 数据模型不在本轮**。详细清单、交互变化与已知限制见
[CHANGES-v6.md](CHANGES-v6.md)。

一句话摘要：

- 修复 v4/v5 的"结束一段后开始按钮永久变灰"（`StartPolicy` + 5 条单测）；
- 顶栏：时长右上、诊断收进 `(i)`、协议移入设置；统计三格合并为一行；
- 最近 10 拍默认折叠；主操作 56dp、次级与图标 48dp；打点加触觉反馈；
- 专注模式（持久化，巨大心率 + 打点圆钮）、记录时保持常亮、未连接也可开始；
- PC 验收：84 测试 0 失败 / lint 0 error、9 warning / APK 6.03 MB。

分支 `v6-ui-p0p2` 从 `main`（v5）切出，v5 源码与 APK 原样保留。

