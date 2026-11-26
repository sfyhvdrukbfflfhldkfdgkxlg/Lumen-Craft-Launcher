# Android 进程和运行时

服务端由独立的原生 PIE 可执行文件加载 OpenJDK HotSpot。Android Activity 和前台服务继续使用 ART。父应用退出后，子进程的监视线程会结束 JVM。程序通过 JNI Invocation API 创建虚拟机、核验 Java 版本，再调用该 OpenJDK 的启动类以执行服务端 JAR 的 Main-Class。标准输入接收指令，标准输出与错误合并，界面只保留最近 24,000 个字符。

## 启动与测试

安装服务端后打开控制台。首次运行可选择下载相应 Java，也可导入本地 Android OpenJDK ZIP。应用根据实际 JVM 宿主的 ELF Machine 选择下载文件，校验 SHA-256 和大小，在临时目录解压并运行 JVM 自检，通过后提交到私有目录。控制台中输入的 MiB 数值为最大 Java 堆容量，原生库和系统线程还会占用额外内存。用户在界面同意 Minecraft EULA 后才能启动服务端，输入 stop 会走正常的游戏保存及退出流程。

运行时目录列出 Java 8、17、21 的 ARMv7、ARM64、x86 和 x86_64 下载项，Java 25 列出 ARMv7、ARM64 和 x86_64。这些是上游发布的二进制组合。实测范围见项目根目录测试报告。不同版本 HotSpot 的最小 Android API 及 CPU 指令要求可能不同，本机运行前的 JVM 自检会报告加载失败。每个 Java 大版本当前保留一套运行时。

如需离线，先按目录中的 URL 下载对应 TAR.XZ，再运行下列命令。ZIP 根目录会包含 lib 及上游许可文件。第三方本地导入的运行时将以应用权限执行，只应选择自己信任的 Android 构建。

```powershell
python runtime/tools/prepare-runtime.py jre8-android-x86.tar.xz java8-x86.zip
```

## 构建与依赖

原生源代码位于 src/main/cpp。Windows 下可使用 Android NDK r24 的 Clang 重新编译 4 个 ABI。Gradle 会打包 app/src/main/jniLibs 中现成的共享库与独立 PIE。app 需要提取原生库以便从 APK 安装目录启动可执行文件。图形检测在单独线程建立 EGL pbuffer 上下文，读取实际 GPU 标识、GLES 和 GLSL 版本，再清屏并验证像素回读。

```powershell
.\runtime\build-native.ps1 -Ndk C:\Android\ndk\24.0.8215888
```

运行时目录记录 AngelAuraMC 独立 OpenJDK 构建项目的 release asset 摘要。运行时由应用按需从该上游下载，许可及第三方声明保留在运行时内。提供方的特定构建脚本、源码及发布记录见 [Android OpenJDK 构建](https://github.com/AngelAuraMC/angelauramc-openjdk-build) 和 [发行文件](https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/tag/download)。

应用解压 XZ 使用 org.tukaani:xz:1.12，许可证为 0BSD。EGL、GLES 和 JNI 使用 Android NDK 平台接口。[XZ for Java](https://tukaani.org/xz/java.html)

编号版本 1.7–1.16 的官方服务端在首次启动时获取并校验对应的 Mojang Log4j 配置文件。宿主亦启用 Log4j 消息查找禁用参数。快照尚未建立逐版本日志配置映射。[Mojang 服务端公告](https://www.minecraft.net/en-us/article/important-message--security-vulnerability-java-edition)

Java 25 上游 Linux/Bionic JRE 需要 Android 的 libc++_shared.so。APK 对 Java 25 的三种发行 ABI 打包 Google NDK r28c（28.2.13676358）C++ 运行库，各 ELF 分别按上游构建对齐，ARM64 和 x86_64 为 16 KiB，ARMv7 为 4 KiB。上游完整声明打包在 assets/licenses/Android-NDK-r28c-NOTICE.txt。构建工具用 NDK r24 的 llvm-strip 移除调试段。在 Windows 执行下列命令，可从 Google 的 ZIP 中仅获取所需文件并校验源文件、裁剪产物及 NOTICE 的 SHA-256。

```powershell
.\runtime\build-native.ps1 -Ndk C:\Android\ndk\24.0.8215888 -RefreshCxx
```

已有 NDK r28c 时可将 -RefreshCxx 换为 -CxxNdk C:\Android\ndk\28.2.13676358，直接使用本地文件。固定哈希在 runtime/tools/vendor-cxx.py 中。[Google NDK r28c Windows 压缩包](https://dl.google.com/android/repository/android-ndk-r28c-windows.zip)
