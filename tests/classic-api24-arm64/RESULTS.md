# Android 7.0 ARM64 客户端运行验收

2026-11-26 在 Windows x64 上用 Android Emulator 的单核 QEMU 软件模拟运行 Google 官方 Android 7.0 ARM64 镜像。客体报告 API 24、`arm64-v8a`、`uname -m` 为 `aarch64`。JNI 宿主、软件光栅器与窗口桥均取自启动器的正式构建输入。游戏文件是从官方元数据下载并完成 SHA-1 校验的 `rd-132211`。运行环境为目录所列的 Android AArch64 OpenJDK `1.8.0_482`。

| 检查 | 实际结果 |
| --- | --- |
| Java 运行时 | ARM64 宿主调用 `JNI_CreateJavaVM`，读取 `java.specification.version` 为 `1.8`，独立探测退出码 0 |
| 游戏入口和纹理 | 原版 `RubyDung` 主循环读取 JAR 内纹理并生成世界，实际收到 156 帧 320 × 240 RGBA |
| 实际输入 | 通过应用所用的会话协议发送键盘和鼠标事件。两个采样帧分别含 197 和 268 种颜色，帧哈希不同 |
| 方块与存档 | 发送 Enter 保存基线世界，执行瞄准与拆除，关闭会话并读取游戏的退出存档。两份解压后的 `level.dat` 等长，恰好一个字节变化 |
| 退出 | 游戏保存后正常关闭，子进程退出码 0 |

本次经 adb 启动游戏运行核心，覆盖 API 24 的原生库加载、HotSpot、桌面 OpenGL 软件光栅化与 Minecraft 的输入和存档。系统 `zygote64` 在 `libart.so` 的 `artQuickResolutionTrampoline` 中反复发生 SIGSEGV，测试期间暂停该系统服务。此设备的 Android Activity、包管理器、实体 GPU 与触摸屏仍待验证。模拟 CPU 的帧率无法用于估计手机性能。运行日志中的初始 `FileNotFoundException` 来自原版游戏第一次尝试读取新目录中的存档，之后已实际保存。

查看[会话结果](session.json)、[输入前画面](frame-0.png)、[输入后画面](frame-20.png)、[原始日志](game.log)和[镜像及二进制校验值](environment.json)。

## 复测

在专用 ARM64 Android 设备的 `/data/local/tmp/lumencraft-qa/java8` 准备完整的同版本 Java home，并将 `app/src/main/jniLibs/arm64-v8a/liblumenjvm.so` 放到 `/data/local/tmp/lumencraft-qa/liblumenjvm.so` 并赋予执行权限。在项目根目录执行以下命令，将示例设备序号和官方 JAR 路径替换为本机实际值。

```powershell
python client/test_session.py --adb adb --serial emulator-5578 --bridge app/src/main/assets/classic-bridge.jar --native app/src/main/jniLibs/arm64-v8a --game C:/games/rd-132211.jar --results C:/temp/lumen-arm64-frames
```
