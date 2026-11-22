# 构建环境说明

## 标准环境（绝大多数机器）

x86-64 主机 + JDK 17 + Android SDK（platforms;android-34、build-tools;34.0.0）：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

AAPT2 由 AGP 自动从 Google Maven 下载 x86-64 二进制，无需任何额外配置。

---

## ARM64 主机（如 arm64 Linux / Termux 容器 / Apple Silicon 下的 Linux 环境）

**问题**：Google 只发布 x86-64 版的 `aapt2`（来自 `com.android.tools.build:aapt2:*:linux`），
官方 build-tools 的 linux 包同样只有 x86-64。在 aarch64 上直接跑会报：

```
java.io.IOException: Cannot run program ".../aapt2": error=2, No such file or directory
```

（`readelf -p .interp` 会看到 `/lib64/ld-linux-x86-64.so.2` 不存在，所以是"找不到 loader"而非权限问题。）

**解法**：用 `qemu-user-static` 运行 x86-64 的 aapt2，并用
`-Pandroid.aapt2FromMavenOverride=` 指向包装脚本。

### 一次性准备

```bash
# 1) 模拟器与 x86-64 基础库
apt-get install -y qemu-user-static

mkdir -p /opt/x86root && cd /opt/x86root
# 需要 libc6 / libgcc-s1 / libstdc++6 / zlib1g 的 amd64 包
for f in libc6_*_amd64.deb libgcc-s1_*_amd64.deb libstdc++6_*_amd64.deb zlib1g_*_amd64.deb; do
  dpkg-deb -x "$f" root
done

# 2) x86-64 版 aapt2
curl -LO "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.5.2-11315950/aapt2-8.5.2-11315950-linux.jar"
unzip -o aapt2-8.5.2-11315950-linux.jar aapt2 -d /opt/aapt2x

# 3) 包装脚本
cat > /opt/bin/aapt2 <<'EOF'
#!/bin/bash
L=/opt/x86root/root/usr/lib/x86_64-linux-gnu
exec /usr/bin/qemu-x86_64-static -L /opt/x86root/root \
  "$L/ld-linux-x86-64.so.2" --library-path "$L" /opt/aapt2x/aapt2 "$@"
EOF
chmod +x /opt/bin/aapt2
/opt/bin/aapt2 version   # 应输出 Android Asset Packaging Tool 2.19-11315950
```

### 构建

```bash
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/opt/bin/aapt2
./gradlew :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=/opt/bin/aapt2
./gradlew :app:lintDebug -Pandroid.aapt2FromMavenOverride=/opt/bin/aapt2
```

> 只要构建机器是 x86-64，**不需要**任何 `-Pandroid.aapt2FromMavenOverride`。

同样的参数也能写进 `gradle.properties`：

```properties
android.aapt2FromMavenOverride=/opt/bin/aapt2
```

但只在 arm64 机器上写，否则会绑死一个不存在的路径。

---

## 本次交付的验证记录

在 aarch64 Linux + qemu-user-static 环境下实际执行并通过：

| 检查 | 结果 |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL，产出 `app-debug.apk`（约 6.0 MB） |
| `:app:testDebugUnitTest` | 27 tests, 0 failed |
| `:app:lintDebug` | 0 error，15 warning（均为 UI 建议类） |
| APK 清单 | `minSdk 26 / targetSdk 34`，`foregroundServiceType="health"`，权限齐全 |
