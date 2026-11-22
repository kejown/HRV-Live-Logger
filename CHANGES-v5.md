# v5 交付说明（PC 静态优化）

- 基线：`RRLogger_pc_v4_r2/RRLogger`（v4，代码零改动）
- 本目录：`RRLogger_v5_pc_opt/`（从 v4 复制后修改，v4 目录未动）
- 日期：2026-11-22
- 定位不变：**运行目标仍然只有 Android 手机**，PC 只是构建主机。

## 验收结果（PC 第一级）

| 项 | v4 基线 | v5 |
|---|---|---|
| 单元测试 | 70，0 失败 | **79，0 失败** |
| lint | 0 error / 16 warning | **0 error / 16 warning** |
| `assembleDebug` | 成功，6.29 MB | **成功，6.29 MB** |
| APK sha256 | `70265930…A584DF` | `9C679AAC…3F6A6` |

真机第二级（BLE 连接、后台 30 分钟、杀进程恢复、Kubios 导入）**本轮未执行**，见文末。

## 改动清单

### 1. 导出改为分页流式写（`export/RrExporter.kt`、`data/Daos.kt`、`data/RrRepository.kt`）

v4 把整段会话的 `List<Int>` 读进内存、拼成一个 String 再整体写出。8 小时记录约 29k 行
（≈200KB），不是致命问题，但峰值内存随记录时长线性增长。

现在：

- 新增 `RrRecordDao.pageRrMs(sessionId, offset, limit)`，按 `timestampMs ASC, id ASC` 分页（每页 2000 条）；
- 导出循环用 `BufferedWriter` 逐行写 `rrMs\n`，不再构造完整 String；
- MediaStore 路径写入失败时删除 `IS_PENDING` 的残件，不再在 `Download/RRLogger/` 留下 0 字节文件；
- 文件内容格式不变：每行一个整数毫秒，无表头、无单位、无逗号。

### 2. `ticksToMs` 取整修正（`ant/RrCalculator.kt`）

README 第 5 节明确写了「`rrMs = diff * 1000 / 1024` 用四舍五入而不是截断」，但 v4 的实现是
向上取整（ceiling）：每个非整数毫秒都会 +1ms，长期均值系统性偏大约 0.5ms。

v5 改为最近整数取整，并补两条测试：

- `ticks to ms rounds to nearest not ceiling`：1023→999、2047→1999（ceiling 会得到 1000/2000）；
- `rounding error stays within half a millisecond for every 16 bit diff`：遍历 1..65535，
  最坏误差必须 ≤0.5ms（用 v4 的实现这条测试会失败）。

> 影响：单拍最多变化 1ms，Mean RR 会有 ≤0.5ms 量级的差异；RMSSD 等差分指标几乎不受影响。
> 这属于修正到文档既定语义，不是算法改动。

### 3. 数据质量分级抽成纯函数（`ui/QualityAdvisor.kt` + 测试）

阈值（最少 20 样本、2%/5%/15%）原先写在 `MainActivity.renderQuality` 里，无法单测。
现在 `QualityAdvisor.evaluate(dropped, raw)` 返回 `Level` + `droppedRate`，Activity 只做
文案与配色映射。新增 7 条测试覆盖样本量下限与每个阈值两侧。

界面文案与配色与 v4 一致。

### 4. 依赖版本集中到 Version Catalog（`gradle/libs.versions.toml`）

`agp` / `kotlin` / `ksp` / AndroidX / Room / 协程 / 测试依赖的版本号集中到一处。
**版本号一个都没改**，只是搬家。ANT+ SDK 仍是 `app/libs/*.aar` 的 fileTree，不进版本目录。

### 5. 通知从服务里拆出（`service/ServiceNotification.kt`）

`RecordingService` 原先 545 行，同时管会话、采集、统计、恢复、通知、前台服务与错误态。
本轮把通知通道、文案、PendingIntent、`startForeground` 的 API 分支与降级逻辑整体搬到
`ServiceNotification`，服务通过 `State` 快照传状态。

通知文案、通知 id、channel id、停止按钮的 action 与 v4 完全一致；服务降至约 440 行。
`RecordingService` 仍保留 `ACTION_START` / `ACTION_STOP`（停止按钮的 PendingIntent 指向它）。

## 本机复现（Windows）

```powershell
$env:JAVA_HOME   = "E:\dsh workspace\.tools\jdk17\jdk-17.0.21.1+2"
$env:ANDROID_HOME = "E:\dsh workspace\.tools\android-sdk"
$env:ANDROID_USER_HOME = "E:\dsh workspace\.tools\android-home"
$env:GRADLE_USER_HOME  = "E:\dsh workspace\.tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="E:\dsh workspace\.tools\tmp"'

cd E:\dsh workspace\RRLogger_v5_pc_opt
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug     --no-daemon
.\gradlew.bat :app:lintDebug         --no-daemon
```

这台机器的用户目录对 JVM 写入受限（PowerShell 可写，`java.exe` 报 `AccessDeniedException`），
所以需要 `JAVA_TOOL_OPTIONS` 指走临时目录，`ANDROID_USER_HOME` 决定 debug keystore 位置。
**这两项是机器绕行，不属于工程改动**；换机器按 `scripts/pc-setup.sh` 常规流程即可。

## 仍未做 / 需真机

| 项 | 状态 |
|---|---|
| R8 / resource shrinking | 未做。反射驱动 ANT+ SDK，混淆后必须真机验证反射调用 |
| BLE 重连指数退避 | 未做。涉及连接状态机，需真机反复断连 |
| `MainActivity` 权限/绑定/回调三分 | 未做。纯结构重排但无测试兜底，建议真机在手时再做 |
| 真机四项验收 | **未执行**（本机 adb 无设备） |

真机复测重点：**导出文件必须逐行核对**（分页顺序改动过读取路径），其余三项照旧。
