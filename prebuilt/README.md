# 预编译 APK

| 文件 | 版本 | 内容 |
|---|---|---|
| `RRLogger-v6.2-debug.apk` | versionCode 3 / versionName 1.2 | v6.2：P0+P2、专注模式、打开即连、居中/橙色微调 |

- **sha256**：`3F5CB786C690D729E7675192BDEC680B9689E4ADE6439E1991B7A07CFBFD6BD3`
- **大小**：6,316,434 字节
- **签名**：本机 Android debug keystore。可以覆盖升级同签名的旧 debug 包，**不能上架应用商店**。
- **对应源码**：提交 `a0bdf22`（分支 `main` / `v6-ui-p0p2`）
- **安装**：

```powershell
adb install -r RRLogger-v6.2-debug.apk
```

- **构建**：`./gradlew :app:assembleDebug`，环境与验证记录见 [../CHANGES-v6.md](../CHANGES-v6.md)。

> 注意：用别的机器重新构建会生成另一把 debug key，届时这个 APK 无法覆盖安装，
> 需要先卸载或改用同一把 keystore。正式分发请走 release 签名。
