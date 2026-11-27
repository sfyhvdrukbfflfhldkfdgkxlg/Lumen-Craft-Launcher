# LumenCraft 验收与兼容矩阵

## Alpha1.2.0

| 环境 | 操作和结果 |
| --- | --- |
| Windows x64，NDK r24 + Rust 1.85.1 | 重建全部四种 ABI（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`）的 `liblumengl.so`（扩展固定管线、矩阵栈、`GL_ALPHA_TEST`、雾效、2D 纹理、顶点数组、显示列表、无 panic Rust GLSL 转换器）、`liblumencap.so`（EGL/GLES + `dlopen` 动态 Vulkan 探测）与 `liblumenjvm.so`（`LUMEN_JVM_ARGS_FILE` + `LUMEN_GLIBC_LIB`），全部通过 `-Wall -Wextra -Werror --no-undefined` 与 16 KiB 页对齐链接。 |
| Windows x64，JDK 17，Gradle 8.9 | `build.ps1 -Release -UseInstalledAlphaKey` 通过 `:app:assembleRelease`、`:app:lintVitalRelease` 及全部四套 JVM 回归：`tests/local`（18 项，覆盖备份/恢复、结构化崩溃诊断、硬件性能推荐与配置回滚、模组语言与运行时按键扫描）、`tests/installer`（覆盖断点续传、SHA-1/256、Fabric/Quilt/NeoForge/Forge/LiteLoader/OptiFine、`MirrorBench` 与 `LaunchPlan` 装配）、`tests/controls`（12 项，含中文化按键标签与重复保护）、`tests/network`（39 项网络/模组管理/房间邀请 + 37 项整合包/迁移/AutoModpack 回归）。 |
| Windows x64，联网实测 | `tests/network/run.ps1 -Live` 通过 43 项 `NetworkTest`（真实下载并校验 Sodium 1.21.1 `1,574,609` 字节 JAR、SHA-512 反查、房间原版邀请准备 `rd-132211`）和 38 项 `PackTest`（实时下载校验 Fabulously Optimized 14.1.2 `.mrpack`）。`tests/server/run.ps1` 实测通过 PaperMC Fill v3 官方 API 下载并校验 Paper 1.21.1（`io.papermc.paperclip.Main`）以及 NeoForge 官方 Maven 下载并校验 NeoForge 1.21.1 安装器（`net.minecraftforge.installer.SimpleInstaller`）。 |
| Release 签名与校验 | `tools/package.py verify` 通过五份 `Alpha1.2.0`（`versionCode=4`）Release APK 的载荷一致性、v1/v2 签名、证书固定摘要（与 `Alpha1.0.0`/`Alpha1.1.0` 一致：`517e9aad...`）、`zipalign`、包名、ABI、6 个原生 `.so` 完整性与非调试标志检查。通用包 SHA-256 为 `cb5b28cfaafa3b9c572fcba11081fbb29a71d73d9fa2e2731310db2a0a507d60`。 |

### Alpha1.2.0 仍待后续设备验证项

- 本机当前未挂载物理 Android 手机或已解压的本地模拟器镜像，因此 `Alpha1.2.0` 尚未在本机模拟器复测 Android UI 与 `liblumengl.so` 新扩展项的设备像素回读（CI 工作流 `.github/workflows/android-release.yml` 已修复模拟器超时退出并会在推送到 GitHub 后自动执行 Android 10 x86_64 覆盖升级验收）。
- 现代 Minecraft 客户端（LWJGL 3，如 1.13–1.21+）已完成文件校验、加载器安装与 `LaunchPlan` 启动参数/类路径装配，但 Android 端原生 LWJGL 3/GLFW 窗口、OpenAL 音频及 Sodium/Iris 光影全链路进入 3D 世界仍待独立原生适配与实机验收。
- 双台物理手机跨网陶瓦组网、中继切换、真机多 GPU（Adreno/Mali/PowerVR）功耗、温度与 60/90/120/144 Hz 帧时间曲线仍待物理设备实测。

## Alpha1.1.0 历史记录

| 环境 | 操作和结果 |
| --- | --- |
| Windows x64，JDK 17 | `tests/network/run.ps1 -Live` 通过 37 项网络和安装检查及 32 项整合包检查。真实下载并验证了 Sodium 1.21.1 的 1,574,609 字节 JAR，房主端通过 SHA-512 查询到对应 Modrinth 版本。另一次全流程从原版邀请在新目录中下载了 Mojang 的 rd-132211，并写出可供本启动器识别的实例。 |
| Windows x64，离线夹具 | 模组邀请覆盖精确版本优先于更新版本、递归前置、跳过禁用和纯服务端模组、未知 JAR 拒绝、摘要错误拒绝、重复安装和已有实例被修改后的拒绝。房间安装收据不含联机码。 |
| Android 6.0 API 23，x86 模拟器 | NDK r24 + Rust 1.85.1 重建四种 ABI 的 GLES 2 兼容库。将 x86 库和独立探针推入设备，依次绘制 QUADS、QUADS、QUAD_STRIP、POLYGON、QUADS，五次中心像素均读出 RGB 0,255,0。非法绘制模式返回 GL_INVALID_ENUM，未实现的计算着色器入口返回空。[像素探针](tests/graphics/pixels.cpp) |

Android 6.0 x86 模拟器已将 Alpha1.0.0 主包覆盖升级到 Alpha1.1.0 通用 Release APK（版本代码 3，UID 与首次安装时间一致），并完成同包陶瓦建房、VPN 建立及退出后 TUN 接口回收。见[界面与安装验收](tests/app/Alpha1.1.0.md)与[房间设备记录](tests/rooms/Alpha1.1.0.md)。

## Alpha1.0.0 历史记录

2026-11-26。在 Windows x64 上构建主应用和联机组件。原版世界、认证系统、资源平台和渲染后端的测试分别记录。此版本实际可玩的范围限于五个列入客户端适配清单的远古版本。

## 客户端、安装及服务端（历史实机与模拟器证据）

| 测试环境 | 样本 / 操作 | 已验证范围 |
| --- | --- | --- |
| Android 6.0，API 23，x86 软件 CPU 模拟器，Android OpenJDK 8 | Mojang 原版 rd-132211 | 独立宿主真实运行游戏，收取 156 帧 320×240 RGBA，传入移动、转向、拾取及拆除输入，游戏保存后恰有一个方块字节改变，正常退出。[设备证据](tests/classic-api23/RESULTS.md) |
| Android 7.0，API 24，ARM64 软件 CPU 模拟器，OpenJDK 8 | 同一官方 rd-132211 | 收到 156 帧，完成移动、视角、拆除与真实存档对比，子进程退出 0。[ARM64 证据](tests/classic-api24-arm64/RESULTS.md) |
| Android 6.0 x86，安装的主应用 | 正常点击启动远古版 | 从游戏页核验原版文件，按需启用 Java 8，显示带虚拟按键的实际游戏画面；正常退出后保存 level.dat，再次打开时创建备份。[游戏运行截图](tests/app/client-api23.png) |
| Windows x64，JDK 17，同源软件渲染及输入桥 | rd-132211、rd-132328、rd-160052、rd-161348、rd-20090515 | 每个版本进真实世界、移动、改动一个方块并保存。[逐版本帧与存档结果](tests/classic-host/RESULTS.md) |
| Android 6.0 x86 / Android 7.0 ARM64，原生宿主 | Java 8、17、21、25 | 实际创建对应 HotSpot 并核对 `java.specification.version`。 |
| Android 6.0 x86，独立服务端子进程 | Vanilla 1.12.2 / Java 8 | 启动至 EULA 确认，实际生成配置，正常退出。 |
| Windows x64，JDK 17 | Fabric 1.20.1、Purpur 1.20.1/1.21.1、Paper 1.21.1、NeoForge 1.21.1 | 服务端校验下载、签名/摘要核对与入口清单验证。[发行版矩阵](tests/server/RESULTS.md) |

## 当前功能边界总览

| 领域 | 已完成并验证 | 仍待完成或未真机验收 |
| --- | --- | --- |
| 客户端运行 | 五个 RubyDung 远古版完整进世界与存档；现代版本支持文件校验、加载器安装与 `LaunchPlan` 参数/类路径装配；`liblumenjvm.so` 支持外部 JVM 参数文件与独立 glibc 兼容目录挂载。 | 现代客户端（LWJGL 3）Android 端原生 GLFW 窗口、OpenAL 音频、通用 AWT 窗口与完整进入 3D 世界运行。 |
| 桌面图形 | OSMesa 固定管线（远古版）；`liblumengl.so` 扩展 GLES 2 兼容层（矩阵栈、`GL_ALPHA_TEST`、雾效、2D 纹理、顶点数组、显示列表、Rust GLSL 120–460 → ES 100/300 转换器）；`liblumencap.so` EGL/GLES + 动态 Vulkan 探测。 | 完整桌面 OpenGL 3.x–4.6 管线、Vulkan 渲染后端执行、外部驱动插件加载以及 Sodium/Iris/OptiFine 光影实机渲染。 |
| 输入控制 | FCL v21 布局导入导出、虚拟键鼠/摇杆、实体键鼠、手柄双摇杆与扳机、可选陀螺仪视角、结合运行时转储与模组语言文件的中英文分类按键识别及冲突提示。 | 面向现代客户端的实时游戏内场景自动切换（菜单/游戏状态桥接）与全部 FCL 扩展脚本控件。 |
| 资源与实例 | Modrinth 资源搜索与依赖安装；Fabric/Quilt/NeoForge/Forge/LiteLoader/Cleanroom/本地 OptiFine；Modrinth/CurseForge/MultiMC/HMCL 整合包导入与 FCL/PCL 目录迁移；模组元数据解析、冲突/缺失前置诊断、一键补全、版本锁定、带快照批量更新与一键回滚。 | CurseForge 外部模组直接在线下载（需用户合法 API Key）与全量更新日志展示。 |
| 联机与服务端 | Java 状态握手、SRV、FML2/legacy 模组清单探测、`.well-known` 清单与 AutoModpack 同步、内置陶瓦联机与环境邀请；Vanilla/Fabric/Paper/Purpur/NeoForge/Forge 服务端、配置编辑器、玩家管理指令、插件/模组管理、`/proc` 内存与过载监控、定时备份与自动重启。 | 双物理手机跨网 P2P/中继实测、长期前台保活与双实例同跑压测。 |
| 账户与硬件 | 离线、Microsoft 设备代码与合法第三方 Yggdrasil（`authlib-injector`）认证，Keystore 加密存储与皮肤/披风档案查看/本地皮肤导入；官方源/BMCLAPI/自定义 HTTPS 测速切换；结构化崩溃诊断与配置回滚。 | 真实微软正版在线多人联机实测、物理手机与平板多刷新率（60/90/120/144 Hz）实测。 |

完整工程位于本文件同目录，安装包、源码归档及 SHA-256 清单位于 `dist`。
