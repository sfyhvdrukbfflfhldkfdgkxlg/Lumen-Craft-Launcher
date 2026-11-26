# Android 运行时实测

设备为 AOSP Android 6.0（API 23）x86，Google Emulator 30.8.4，Windows 主机 TCG 软件 CPU。此处将命令行原生宿主、应用 UID 测试和桌面测试分别记录。

| 检查项 | 实际结果 |
| --- | --- |
| APK 原生代码 | NDK r24 重新编译 arm64-v8a、armeabi-v7a、x86、x86_64；x86 实际执行，其余三个 ABI 在本轮仅验证构建 |
| Android OpenJDK 8 x86 | 应用自研宿主调用 libjvm.so 后读取 java.specification.version，返回 1.8 |
| 独立服务端进程 | 通过 ADB shell 运行发布宿主及经官方 SHA-1 验证的 Vanilla 1.12.2 server.jar。记录 Starting minecraft server version 1.12.2，实际生成 server.properties 与待确认的 eula.txt，正常退出 |
| APK 安装目录、私有 JVM 加载 | 以安装包的实际 UID 10055 运行目标包 Context，私有测试目录中导入 JRE ZIP；正常解压、检查 ELF，使用 APK 安装目录中的 liblumenjvm.so 创建 JVM，通过 Java 大版本自检 |
| Android 6 下载 TLS | 初次系统信任库验证缺少发行文件域名根证书。修复后在上述真实应用 UID 中，github.com 返回 302 / TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256，release-assets.githubusercontent.com 返回 200 / TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256，读取真实 XZ 文件标识 |
| GLES 能力和像素回读 | 应用 UID 下 GraphicsProbe 显示 Android Emulator OpenGL ES Translator (Google SwiftShader)，GL 字符串为 OpenGL ES 2.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)，GLSL 为 OpenGL ES GLSL ES 1.0.17。1 像素清屏、回读通过 |
| 固定管线小范围适配 | 同一环境 LegacyGraphics.check 返回 GL_QUADS → GLES 2.0 PASS (RGBA readback) |
| 官方库文件 | x86 Java 8、Java 17、Java 21 的 TAR.XZ 全量下载后核对发布者 SHA-256。Java 8 转为 ZIP 并通过设备导入。Java 17 和 Java 21 均实际在同一 Android 6 x86 客体调用 JNI_CreateJavaVM 并验证各自的 java.specification.version。其余目录条目未逐一运行 |
| 额外服务端 | Fabric 1.20.1 和 Purpur 1.20.1 的 Windows JDK 17 测试记录位于 tests/server/RESULTS.md |

x86 日志中 API 23 链接器提示较新的 DT_FLAGS_1 标志，两个原生 JVM 检查均完成。Android 服务端测试止于 EULA，没有接受条款、创建世界或连接玩家。前台服务的持续保活、Java 17/21/25 实际游戏会话及最终 Java 25 配套库的设备复测、物理 GPU/CPU 和更高 API 仍需设备测试。

旧系统 TLS 回退仅对 github.com 及 release-assets.githubusercontent.com 的运行时下载连接生效，沿用平台主机名验证与 X.509 证书路径验证。APK 内保留 Sectigo E46、R46 和 ISRG Root X1 公共根证书。证书来源和 DER SHA-256 指纹来自 [Mozilla CA Extract](https://curl.se/docs/caextract.html)。[Sectigo 根 CA](https://www.sectigo.com/knowledge-base/detail/Sectigo-Public-Intermediates-and-Roots) 和 [ISRG 根 CA](https://letsencrypt.org/certificates/)。



Android 7.0 / API 24 ARM64 TCG 上，固定 SHA-256 的 Java 25 发行包最初报告缺少 libc++_shared.so。在 LD_LIBRARY_PATH 加入 NDK r24 ARM64 的该标准库后，同一发布宿主返回 OpenJDK specification 25 (requested 25)，退出码 0。最终安装包采用 NDK r28c 的 libc++，两个 64 位 ABI 的 ELF 对齐为 16 KiB。源及裁剪产物的哈希已验证，r28c 配套库组合尚待在该 ARM64 客体复测。
