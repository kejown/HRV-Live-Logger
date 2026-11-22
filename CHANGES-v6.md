# v6 交付说明（P0 + P2，UI 结构/交互层）

- 基线：v5（`RRLogger_v5_pc_opt`，分支 `main`）
- 本目录：`RRLogger_v6_ui_p0p2`，git worktree，分支 `v6-ui-p0p2`
- 范围：只做 UI 文档里的 **P0（结构降噪/可用性）** 与 **P2（专注模式/常亮/未连接可开始）**
- 明确没做：**P1 视觉升级**（主色、字体、区间条、波形光晕）与 **Lap 数据模型**

## 验收结果（PC 第一级）

| 项 | v5 | v6 |
|---|---|---|
| 单元测试 | 79，0 失败 | **84，0 失败** |
| lint | 0 error / 16 warning | **0 error / 10 warning** |
| `assembleDebug` | 6.29 MB | **6.02 MB**（versionCode 3 / versionName 1.2） |
| APK sha256 | `9C679AAC…3F6A6` | `3F5CB786…D6BD3` |

预编译 APK 直接放在仓库根目录：`RRLogger-v6.2-debug.apk`（versionCode 3 / versionName 1.2），
`adb install -r` 即可覆盖安装；debug 签名，换机器重新构建会换 key。

真机视觉与触感**未验证**（本机 `adb devices` 仍为空）。

## P0：结构降噪与可用性

1. **修复"一段结束后不能开始第二段"**
   v4/v5 的 `canStart = 可用 && !recording && sessionId == null`，而 `stopSession()` 不清 `sessionId`，
   导致一次 App 生命周期内只能记一段。现在抽成纯函数 `StartPolicy.canStart(可用, recording)`，
   并用 5 条单测锁住，其中包含"结束后立即开始"和"未连接也允许开始"。
2. **顶栏重排**：时长移到右上（等宽字体），诊断信息收进 `(i)` 对话框，协议切换从顶栏移入设置。
   顶栏最终是：连接点 · 设备名 · 时长 · 诊断 · 设置。
3. **统计合并**：原来的"拍数/丢弃/时长"三格缩成心率卡内一行 `1542 拍 · 丢 3`；
   数据质量从独立卡片变成心率卡内的呼吸灯标签（优秀/良好时 900ms 呼吸，异常时常亮）。
4. **最近 10 拍默认折叠**：标题行点击展开/收起（48dp 触控区），数据仍照常入库。
5. **触控区**：主操作 56dp、次级操作与图标 48dp；打点改为先触觉反馈（`CONFIRM` / `VIRTUAL_KEY`）
   再由服务发 Toast。
6. **设置入口**：顶栏齿轮 → 显示与专注对话框；`DisplayPrefs` 用 SharedPreferences 存两个开关，
   不引 DataStore。

## P2：专注模式与记录期体验

7. **专注模式**（持久化）：全屏只保留时长、巨大心率（autoSize 72–180sp）与 96dp 打点圆钮；
   顶栏保留"结束"和"退出专注"；返回键也会退出。通知栏的"结束记录"仍在，双通道可停。
8. **记录时保持屏幕常亮**：设置里可开，只在记录中生效（`FLAG_KEEP_SCREEN_ON`），停止后立即清除。
9. **未连接也能开始记录**：`StartPolicy` 不再要求已连接——`RecordingService.startSession()`
   本来就会主动 `connect()`，断线重连是服务职责；数据源整体不可用（无蓝牙/无权限）时仍拦截并提示。

## 装机反馈修复（同一 v6 分支，versionCode 2 / versionName 1.1）

首版装到手机后暴露了三个问题，已在同一分支修复：

1. **专注模式看不到大心率**
   `tvFocusHr` 用了 `autoSizeTextType="uniform"` + `layout_height="wrap_content"`，
   在手机上被测量成 0 高，整块区域只剩"均 82"。改为固定字号、按位数切换：
   2 位数 150sp，3 位数 112sp（`setFocusHr()`），不再依赖 autoSize。
2. **权限流程**
   启动时只请求"连接"真正需要的权限：蓝牙（12+ 为 SCAN/CONNECT，12 以下为定位）+ BODY_SENSORS；
   通知权限推迟到"开始记录"；权限结果回来后重新计算数据源可用性，不再因为"还没授权"显示
   "数据源不可用"。拒绝时给"去设置"入口；只有蓝牙/定位（以及记录时的身体传感器）会拦住动作，
   通知被拒不影响记录。
3. **打开即连接、未记录也显示实时心率**
   `MainViewModel.connectWhenReady()` 在权限就绪后连接（服务未绑定时挂起，绑定后落地）；
   `RecordingService` 新增监测协程消费 `hr.beats`，未记录时把 `lastHr/lastRrMs` 推给界面。
   记录时仍由 `startCollecting` 走 `beatChannel` 逐拍落库，两条通道互不双写。
   状态文案新增"监测中 · 未记录"，波形在未记录时按 RR 值变化推进。

副作用：打开 App 就会扫描蓝牙（这是"打开即可看心率"的代价）；如果只想导出历史，
可以等连接搜索超时或直接操作导出，不影响已有数据。

### 装机体感微调（versionCode 3 / versionName 1.2）

- **专注模式大心率水平居中**：`tvFocusHr` 是 `match_parent` 宽度，文字默认靠左；加 `gravity="center"`，
  与下方"均 xx"一起在屏幕中线上。
- **平均心率改亮橙色**：新增 `@color/orange`（`#FFA726`），主界面均值胶囊与专注模式"均 xx"
  统一使用；心率主数字仍按区间变色，两者不抢。

## 交互变化（用户能感知）

- 顶栏不再有"协议"文字按钮；协议切换在齿轮菜单第一行。
- "重检"仍在次级操作，但它只刷新可用性，不再等同于"重新连接"。
- 设置里的两个开关点"完成"才保存，"取消"不生效。
- 专注模式会记住；下次打开 App 直接进专注界面（返回键/退出按钮可退出）。

## 已知限制 / 需要真机确认

| 项 | 说明 |
|---|---|
| 专注模式隐藏异常横幅 | 记录中若连接出错，专注界面只看到心率停更，看不到错误文案。可考虑在专注顶栏加状态点，等真机试用后定 |
| 打点仍不是 Lap | 目前只改会话标签；真正的分段记录需要新表 + 迁移 + 导出口径 |
| 无视觉验收 | 字体、间距、专注模式在真实屏幕/强光下的效果都没看过，需要装到手机后截图确认 |
| 设置项少 | 只有专注与常亮；区间条开关要等 P1 区间条落地再加键 |

## 本机复现

```powershell
$env:JAVA_HOME   = "E:\dsh workspace\.tools\jdk17\jdk-17.0.21.1+2"
$env:ANDROID_HOME = "E:\dsh workspace\.tools\android-sdk"
$env:ANDROID_USER_HOME = "E:\dsh workspace\.tools\android-home"
$env:GRADLE_USER_HOME  = "E:\dsh workspace\.tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="E:\dsh workspace\.tools\tmp"'

cd E:\dsh workspace\RRLogger_v6_ui_p0p2
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug     --no-daemon
.\gradlew.bat :app:lintDebug         --no-daemon
```

> 本机 `java.exe` 写用户目录受限的绕行与 v5 相同，属于机器问题，不是工程改动。

## 下一轮建议

1. 把 P1 的视觉项（主色/字体/区间条/波形光晕）在真机截图确认后做；
2. 若要真 Lap：先定导出与 Kubios 的边界（RR 仍是整段，Lap 只作为元数据），再动 schema；
3. `MainActivity` 继续拆（权限/绑定/回调），v6 已经先把它从"什么都管"降到"只管界面编排"。
