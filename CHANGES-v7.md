# v7 交付说明（改名 HRV Live Logger + 实时 HRV 评测）

- 基线：v6.2（`main`，提交 `ddd946f`）
- 本目录：`HRVLiveLogger_v7_hrv`，git worktree，分支 `v7-hrv`
- 范围：按 D 方案的**第一阶段**执行——时域 HRV 指标 + 会话摘要 + 历史详情；
  个人基线趋势留到第二阶段
- 导出格式、数据写入方式、协议层、数据库 schema **都没有改**

## 改名

| 项 | 处理 |
|---|---|
| 应用显示名 | `R-R Logger` → **HRV Live Logger**（启动器、通知标题、通知渠道） |
| 包名 / applicationId | **保持 `com.rrlogger`**。改了会变成新 App，历史会话与数据全部看不到 |
| GitHub 仓库 | 你已改名为 `HRV-Live-Logger`；本地 remote 已同步到新地址 |
| 导出口录 | 保持 `Download/RRLogger/`，避免旧文件路径失效 |

版本：versionCode **4** / versionName **2.0**（改名 + 新功能，可覆盖安装 v6.2）。

## HRV 指标（时域 + Poincaré）

新增纯 Kotlin 引擎 `com.rrlogger.hrv`：

| 指标 | 公式 |
|---|---|
| Mean RR / Mean HR | RR 均值；HR = 60000 / Mean RR |
| SDNN | RR 的样本标准差（n−1） |
| RMSSD | 相邻差值平方均值的平方根 |
| pNN50 | \|相邻差值\| **严格大于** 50ms 的占比 |
| CVNN | SDNN / Mean RR × 100% |
| SD1 / SD2 | SD1 = √0.5 × SDSD；SD2 = √(2·SDNN² − 0.5·SDSD²) |
| 疑似伪影 | 相邻变化超过较小 RR 的 35% 的次数，**只统计不删数据** |

- 少于 20 拍：指标照算但标记 `reliable=false`，界面只显示"样本不足"，不给数字。
- 少于 2 拍：返回 null。
- 不做伪影校正、不改写入库的 RR；Kubios 仍是分析基准。

## 界面

1. **实时 HRV 卡**（主界面，心率卡下方）：最近 **60 秒**滑窗的 RMSSD / SDNN / pNN50，
   右上角显示样本数 `n=xx`；样本不足显示占位与提示。未开始记录也工作（与实时心率一致）。
2. **停止记录后**：弹一次"本会话 HRV"摘要（拍数、平均心率、Mean RR、SDNN、RMSSD、pNN50、
   SD1/SD2、疑似伪影数），同一会话只弹一次。
3. **历史会话**：点击不再直接导出，而是先选动作——"导出为 Kubios 文本"或"查看 HRV 详情"。
4. 摘要与详情都带一句"非医疗建议；如需伪影校正与频域分析，请用 Kubios"。

## 验证（PC 第一级）

| 项 | 结果 |
|---|---|
| 单元测试 | **93，0 失败**（v6 的 84 + 9 条 HRV 公式测试） |
| lint | **0 error / 10 warning**（与 v6 相同） |
| `assembleDebug` | 成功，6.04 MB（versionCode 4 / versionName 2.0） |
| APK sha256 | `85B0EC98…4799E` |
| 真机 | **未验证**（本机无设备）：滑窗手感、摘要弹窗时机、历史交互都要装机看 |

HRV 测试覆盖：恒定 RR 全零、交替 RR 的 RMSSD/pNN50、pNN50 的 50/51ms 边界、
Poincaré 恒等式（SD1²+SD2²=2·SDNN²）、伪影计数、样本不足标记、CVNN 定义。

## 已知限制 / 下一阶段

- **无频域**（LF/HF、总功率）：需要插值重采样 + FFT，且可靠分析至少 2–5 分钟稳定段。
- **无伪影校正**：只统计可疑点；短窗 + 伪影多时 RMSSD 会偏高。
- **无个人基线**：D 方案的第二阶段，用历史会话算个人基线后做"高于/低于自己"的趋势，
  不做绝对评分（避免医疗式误导）。
- 实时卡固定 60 秒，暂不可配置。

## 本机复现

```powershell
$env:JAVA_HOME   = "E:\dsh workspace\.tools\jdk17\jdk-17.0.21.1+2"
$env:ANDROID_HOME = "E:\dsh workspace\.tools\android-sdk"
$env:ANDROID_USER_HOME = "E:\dsh workspace\.tools\android-home"
$env:GRADLE_USER_HOME  = "E:\dsh workspace\.tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="E:\dsh workspace\.tools\tmp"'

cd E:\dsh workspace\HRVLiveLogger_v7_hrv
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug     --no-daemon
.\gradlew.bat :app:lintDebug         --no-daemon
```
