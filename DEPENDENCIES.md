# LumenCraft Alpha1.1.0 交付依赖

| 组件 | 版本 / 来源 | 用途与分发方式 |
| --- | --- | --- |
| Android Framework、NDK | API 23–35 编译接口；NDK r24 | 原生控件、EGL、GLES、JNI、Android Keystore、VPN、前台服务；系统提供平台库 |
| Gradle / Android Gradle Plugin | 8.9 / 8.7.3 | 构建，Apache-2.0；Wrapper 含固定发行包 SHA-256 |
| Kotlin / 标准库 | 2.0.21 | 主界面，Apache-2.0 |
| OpenJDK | AngelAuraMC Android 构建；8、17、21、25 的发布文件由 openjdk.json 按 ABI 固定 | 按需下载并验证 SHA-256，Java 25 的 x86 32 位缺少分发项。运行环境含上游许可证，按相应 OpenJDK GPLv2 + Classpath Exception 及组件声明使用 |
| XZ for Java | org.tukaani:xz:1.12 | 解压受校验的运行环境归档，0BSD |
| Mesa | 3.4.2 的软件栅格器 / OSMesa；官方归档摘要见 client/README.md | 早期客户端固定管线；APK 载有实际使用部分的上游版权与许可 |
| Rust | 1.85.1，四种 Android 目标 | 编译自有四边形索引转换，按目标生成对象并链入 GLES 适配器 |
| Terracotta / EasyTier | Terracotta v0.4.2，EasyTier v2.5.0-terracotta.2 | 集成到主 APK 的 Android 发布库及许可证。rooms/upstream 保存对应源码归档，项目根目录包含整体分发许可 |
| Minecraft | 玩家选择的 Mojang 版本 | 读取官方清单、原始客户端 / 服务端与资源并按 SHA-1 校验；安装包和源码包不分发游戏 JAR、游戏资源或账号 |
| Fabric / Quilt / Purpur / Modrinth | 按实例清单选择并校验 | 元数据与资源连接器。安装文件来自运行时选定的上游项目，实际许可取决于文件 |
| org.json / 测试数据 | Android 运行时自带 org.json；Windows 测试依赖版本和摘要在 tests 下的脚本 | JVM 夹具与 Android Keystore 设备测试，不随主 APK 加入主机测试库 |

主启动器没有引入 FCL、PCL、HMCL 或 PojavLauncher 的启动器核心。FCL v21 布局兼容依据配置格式独立实现，主机测试可使用上游公开配置进行解析验证。

来源见 [Android SDK](https://developer.android.com/tools)、[Gradle](https://gradle.org/releases/)、[Kotlin](https://github.com/JetBrains/kotlin)、[Android OpenJDK 构建与发行](https://github.com/AngelAuraMC/angelauramc-openjdk-build)、[XZ for Java](https://tukaani.org/xz/java.html)、[Mesa 归档](https://archive.mesa3d.org/older-versions/3.x/)、[Terracotta](https://github.com/burningtnt/Terracotta)、[Fabric](https://meta.fabricmc.net/)、[Quilt](https://meta.quiltmc.org/)、[Purpur](https://purpurmc.org/docs/purpur/downloads/) 和 [Modrinth](https://docs.modrinth.com/api/)。
