# 微光 LumenCraft Alpha1.2.0

微光是一套独立实现的 Android Minecraft Java 启动器开发版。支持直接运行五个适配的 RubyDung 远古版本，支持官方全版本文件校验与启动计划装配、Fabric/Quilt/NeoForge/Forge/LiteLoader/Cleanroom/本地 OptiFine 加载器安装、多格式整合包导入、模组依赖诊断与批量更新回滚、AutoModpack 同步、内置陶瓦联机、独立纯服务端（Vanilla/Fabric/Paper/Purpur/NeoForge/Forge）以及独立扩展的 OpenGL ES 兼容层与 Vulkan 探测。现代 Minecraft 客户端的 Android 端原生 LWJGL 3 窗口与音频后端仍在推进，启动器会如实报告各版本就绪状态，不提供虚假入口。

## 安装及使用

宽屏使用侧栏，窄屏使用底部导航，保持**游戏、资源、联机、服务端、设置**五个极简入口，高级设置默认折叠。支持浅色、深色、跟随系统主题与动画开关。

在 Android 6.0（API 23）及以上系统安装 `dist/Alpha1.2.0/LumenCraft-Alpha1.2.0-universal.apk`，也可按 CPU 架构选择独立包。版本代码为 4，与 `Alpha1.0.0` / `Alpha1.1.0` 使用同一签名证书，可直接覆盖升级。

| 入口 | 操作 |
| --- | --- |
| 游戏 | 启动远古版并切换五个已适配版本；安装全版本官方文件及 Fabric/Quilt/NeoForge/Forge/LiteLoader/Cleanroom/本地 OptiFine；导入 `.mrpack`、CurseForge、MultiMC/Prism、HMCL 整合包；管理模组（依赖诊断、一键补全前置、版本锁定、批量更新与一键回滚）、检查 `LaunchPlan` 启动参数、备份/恢复存档与复制/恢复实例。 |
| 资源 | 访问 Modrinth 搜索模组、资源包、光影、整合包与数据包，自动解析前置依赖并按 SHA-512 校验安装。 |
| 联机 | 输入 Java 版服务器地址探测版本、延迟、加载器及 FML 声明模组；支持 `.well-known` 整合包、AutoModpack 清单同步、局域网发现、收藏/历史与内置陶瓦联机房间（支持带环境邀请）。 |
| 服务端 | 安装 Vanilla、Fabric、Paper（Fill v3）、Purpur、NeoForge 或 Forge 服务端，独立 JVM 进程运行；支持 `server.properties` 编辑、玩家白名单/OP/封禁管理、插件/模组管理、实时内存与过载监测、定时世界备份和崩溃自动重启。 |
| 设置 | 管理离线、Microsoft 正版与第三方 Yggdrasil（`authlib-injector`）账户及皮肤/披风档案；展开高级设置可测速并切换官方源/BMCLAPI/自定义 HTTPS 源、查看硬件推荐参数与配置回滚、导入独立 glibc 兼容包及运行 EGL/GLES/Vulkan/LumenGL 图形能力自检。 |

游戏内「按键」菜单支持拖动编辑、手柄双摇杆/扳机、可选陀螺仪视角、结合运行时与模组语言文件的分类按键识别（不覆盖已有自定义布局）以及 FCL v21 布局导入导出。

## 工程与构建

| 路径 | 职责 |
| --- | --- |
| `core/src/main/java` | 官方版本与多加载器安装、`LaunchPlan`、下载源测速与校验、模组管理与回滚、AutoModpack、多格式整合包与迁移、服务器状态、结构化崩溃诊断与性能评估 |
| `app/src/main/java` | Kotlin 原生极简界面、ART 与独立 JVM 进程管理、FCL/手柄/陀螺仪输入、离线/微软/Yggdrasil 加密账户库 |
| `runtime` | C++ JNI 能力与动态 Vulkan 探测、支持外部 JVM 参数与 glibc 兼容目录的 OpenJDK 子进程宿主 |
| `client` | 早期 LWJGL 2 调用桥、OSMesa 软件后端、帧与输入协议 |
| `graphics` | 独立桌面 OpenGL 兼容层（C++ 矩阵栈/纹理/AlphaTest/雾效/顶点数组/显示列表 + 无 panic Rust 图元索引与 GLSL 120–460 → ES 100/300 转换器） |
| `rooms` | 内置 Terracotta / EasyTier 原生库、房间界面、VPN 服务与上游源码 |
| `tests` | 本地、安装、控制、网络/整合包、图形、账户、服务端与 Android 验收测试 |

```powershell
.\build.ps1                            # 开发调试包
.\build.ps1 -Release -Unsigned         # 编译五份 Release APK 并执行主机回归
```

实测结果、当前边界见 [TEST-REPORT.md](TEST-REPORT.md)，依赖与许可证见 [DEPENDENCIES.md](DEPENDENCIES.md)，构建与发布规范见 [docs/releasing.md](docs/releasing.md)。
