# 微光 LumenCraft Alpha1.1.0

微光是一套独立实现的 Android Minecraft Java 启动器开发版。可以运行五个列入适配清单的 RubyDung 版本，安装官方游戏文件，创建服务器专用实例，管理 Modrinth 资源，也可以在手机上用独立 JVM 启动服务端。现代 Minecraft 客户端仍缺少窗口、音频和渲染适配，文件安装成功后不会显示可用的现代客户端启动按钮。

## 安装及使用

宽屏使用侧栏，窄屏使用底部导航。游戏、资源、联机、服务端和设置均可直接访问。设置中可选择浅色、深色或跟随系统，并关闭界面动效。

在 Android 6.0（API 23）及以上系统安装 `LumenCraft-Alpha1.1.0-universal.apk`，也可选择对应 CPU 架构的 APK。版本代码为 3。陶瓦已随主包安装。首次运行游戏需要联网下载官方文件和匹配设备的 Android OpenJDK。已缓存的远古游戏和 Java 8 可以离线使用。

| 入口 | 操作 |
| --- | --- |
| 游戏 | 点“启动远古版”准备文件并打开世界。“切换远古版本”可选择已适配的其他版本。完整官方清单、原版/Fabric/Quilt 文件安装、.mrpack 导入和实例管理位于下方。 |
| 资源 | 访问 Modrinth，按类别搜索。选择项目后可以查看说明、安装支持的资源，或从整合包创建实例。模组须选择兼容的加载器实例。 |
| 联机 | 输入 Java 版服务器地址获取版本、延迟和人数。可通过精确版本准备基础文件，或按服主发布的 HTTPS 清单安装服务器整合包。也可以发现 LAN 世界、管理收藏和打开陶瓦房间。 |
| 服务端 | 选择 Minecraft 版本及服务端发行版，打开控制台，准备 Java、阅读 EULA，再启动服务端。控制台支持发送命令和正常停服。 |
| 设置 | 查看设备能力、实测 EGL 及四边形渲染，或管理离线及微软账户。微软需要已获批的公共客户端 ID。现有 RubyDung 客户端用于离线游戏。 |

游戏中的“按键”菜单可以编辑布局、导入或导出 FCL v21 布局的受支持部分。常规世界可通过系统文件选择器导出或恢复。远古世界在实例根目录保留游戏自己的 `level.dat`，可导出独立文件，并在存在有效世界时于下次启动前保存最多三个不同的自动快照。移除实例可在游戏页恢复，卸载应用会清除其私有数据。

房主在陶瓦房间就绪后，可以分享普通联机码，也可以选择客户端实例分享环境。访客将完整邀请贴入房间页，确认后会安装指定的游戏、加载器、模组版本及必要前置，再连接房间。环境分享支持原版、Fabric 和 Quilt。只分享启用且可由 Modrinth 校验的客户端 JAR，遇到无法识别的文件会显示文件名。配置、存档和私人凭据不会打包进环境。现代客户端目前仍限文件准备。

## 工程与构建

| 路径 | 职责 |
| --- | --- |
| `core/src/main/java` | 版本解析、下载和校验、实例、服务器状态、模组依赖、整合包 |
| `app/src/main/java` | Kotlin 原生界面、ART 与独立 JVM 的进程管理、实际输入、账户档案 |
| `runtime` | C++ JNI 能力探测、OpenJDK 子进程宿主、重编与设备测试工具 |
| `client` | 早期 LWJGL 2 调用桥、OSMesa 后端、帧和输入协议、原版游戏验收程序 |
| `graphics` | 独立桌面立即模式绘制子集。C++ 管理 GLES 2 绘制，Rust 将四边形装配为三角形，设置页自检读取实际像素。 |
| `rooms` | 编入主 APK 的 Terracotta / EasyTier 原生库、房间界面、VPN 服务和上游源码 |
| `tests` | 安装、网络、真实客户端、账户、服务端、输入布局与 Android 运行记录 |

Windows 使用 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后执行以下命令。第一条构建可安装的调试主包，第二条构建未签名的 Release 并运行主机回归，供验证构建使用。

```powershell
.\build.ps1
.\build.ps1 -Release -Unsigned
```

Gradle 默认使用随源码交付的原生二进制。重建它们的命令如下。Rust 部分的交付构建使用 1.85.1，NDK 使用 r24。客户端构建脚本按固定 SHA-256 获取 Mesa 归档。

```powershell
.\runtime\build-native.ps1 -Ndk $env:ANDROID_NDK_HOME
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
.\graphics\build-native.ps1 -Ndk $env:ANDROID_NDK_HOME
python client/build.py --ndk $env:ANDROID_NDK_HOME
.\build.ps1
```

实测设备、功能边界和后续开发清单见[测试报告](TEST-REPORT.md)。组件来源和许可证见[依赖清单](DEPENDENCIES.md)。服主管理接口见[服务器整合包清单](docs/server-packs.md)。
