# 微光 LumenCraft Alpha1.2.0

本版本在 `Alpha1.1.0` 内置陶瓦联机与自适应界面的基础上，重点补齐底层图形兼容层、多加载器与多服务端引擎、模组批量管理与回滚、AutoModpack 同步、下载源测速、手柄/陀螺仪与模组按键识别、第三方 Yggdrasil 认证以及 CI 自动升级验证流程。应用签名证书与 `Alpha1.0.0` / `Alpha1.1.0` 保持一致（`versionCode=4`），支持直接覆盖升级。

## 主要变更

- **桌面 OpenGL 兼容层与 Vulkan 探测**：`liblumengl.so`（C++ + 无 panic Rust）新增模型视图/投影/纹理矩阵栈、`GL_ALPHA_TEST`、线性与指数雾效、2D 纹理采样、客户端顶点数组（`glDrawArrays`）、显示列表（`glGenLists`/`glNewList`/`glCallList`）以及桌面 GLSL `#version 120–460` 到 GLSL ES 1.00/3.00 的转换器（不支持计算/几何/曲面细分着色器时如实拒绝）。`liblumencap.so` 新增 `dlopen("libvulkan.so")` 动态 Vulkan 硬件探测，兼容 Android 6.0（API 23）。
- **JVM 宿主与可选 glibc 兼容目录**：`liblumenjvm.so` 支持通过 `LUMEN_JVM_ARGS_FILE` 载入扩展 JVM 启动参数、控制 headless 模式并挂载应用私有的独立 `runtime/glibc` 兼容目录；新增 `LaunchPlan` 装配器，按 Mojang `version.json` + 加载器配置解析类路径与启动参数。
- **更多加载器、整合包与服务端引擎**：新增 NeoForge、Forge、LiteLoader、Cleanroom（1.12.2）与本地官方 OptiFine JAR 校验安装；整合包导入扩展支持 Modrinth（`.mrpack`）、CurseForge（`manifest.json`）、MultiMC/Prism（`mmc-pack.json`）、HMCL（`mcbbs.packmeta`）与本地 FCL/PCL 目录迁移；服务端新增 Paper（Fill v3 官方 API）、动态版本 Purpur、NeoForge 与 Forge 服务端引擎，并内置 `server.properties` 编辑、玩家权限快捷指令、插件/模组管理、实时 `/proc` 内存与过载/在线监测、定时世界备份与崩溃自动重启。
- **模组深度管理与联机协议**：新增 `ModManager`（解析 JAR 元数据、重复/缺失前置/仅服务端冲突诊断、已安装模组一键补全前置、版本锁定、带快照的批量更新与一键回滚、删除）；服务器探测新增 FML2/legacy 模组清单提取，新增 AutoModpack 公开清单 HTTPS 同步协议。
- **下载源测速、输入增强、第三方认证与智能诊断**：新增官方源/BMCLAPI/自定义 HTTPS 源切换与实时测速（始终强制校验官方 SHA-1/SHA-256 并自动回退官方源）；游戏内新增手柄双摇杆/扳机、可选陀螺仪视角以及结合运行时/模组语言文件的中英文分类按键识别；账户页新增合法 Yggdrasil（`authlib-injector`）第三方认证与皮肤/披风档案管理；崩溃诊断支持结构化归因与一键修复操作，并支持硬件性能推荐与配置快照回滚。
- **CI 升级回归修复**：修复 `.github/workflows/android-release.yml` 在 `ubuntu-24.04` 上模拟器超时退出（exit 124）的问题，增加提前死亡检测与失败日志输出。

## 下载与校验

通用 APK 包含四种原生架构（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`），也可按设备架构下载独立 APK。全部五个安装包的 SHA-256 见同页 `SHA256SUMS.txt`。

## 实测边界

可进入 3D 世界的客户端仍以五个已适配的 RubyDung 远古版本为准；现代版本客户端已完成文件校验、加载器安装与 `LaunchPlan` 参数装配，Android 端原生 LWJGL 3 窗口/OpenAL 音频渲染与物理手机/双机跨网联机实测详见仓库 [TEST-REPORT.md](https://github.com/sfyhvdrukbfflfhldkfdgkxlg/Lumen-Craft-Launcher/blob/Alpha1.2.0/TEST-REPORT.md)。
