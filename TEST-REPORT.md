# LumenCraft Alpha1.0.0 验收与兼容矩阵

2026-11-26。在 Windows x64 上构建主应用和联机组件。原版世界、认证系统、资源平台和渲染后端的测试分别记录。此版本实际可玩的范围限于五个列入客户端适配清单的远古版本。

## 客户端、安装及服务端

| 测试环境 | 样本 / 操作 | 已验证范围 |
| --- | --- | --- |
| Android 6.0，API 23，x86 软件 CPU 模拟器，Android OpenJDK 8 | Mojang 原版 rd-132211 | 独立宿主真实运行游戏，收取 156 帧 320×240 RGBA，传入移动、转向、拾取及拆除输入，游戏保存后恰有一个方块字节改变，正常退出。[设备证据](tests/classic-api23/RESULTS.md) |
| Android 7.0，API 24，ARM64 软件 CPU 模拟器，OpenJDK 8 | 同一官方 rd-132211 | 收到 156 帧，完成移动、视角、拆除与真实存档对比，子进程退出 0。此镜像的系统 ART 服务异常，覆盖内核、Bionic、HotSpot 和游戏运行核心，未验证 ARM64 Activity。[ARM64 证据](tests/classic-api24-arm64/RESULTS.md) |
| Android 6.0 x86，安装的主应用 | 正常点击启动远古版 | 从游戏页核验原版文件，按需启用 Java 8，显示带虚拟按键的实际游戏画面；正常退出后应用目录已有游戏自己写出的 level.dat，再次打开时创建备份。[游戏运行截图](tests/app/client-api23.png) |
| Android 6.0 x86，模拟器 SwiftShader | EGL pbuffer、独立 GLES 子集 | 实际创建 GLES 2 上下文，1 像素清屏回读和 GL_QUADS 经 Rust 转为三角形后的 GLES 2 像素回读均通过。[检测环境与结果](runtime/tests/RESULTS.md) |
| Windows x64，JDK 17，同源软件渲染及输入桥 | rd-132211、rd-132328、rd-160052、rd-161348、rd-20090515 | 每个版本进真实世界、移动、改动一个方块并保存。后两个 ID 对应相同原版 JAR。[逐版本帧与存档结果](tests/classic-host/RESULTS.md) |
| Windows x64，安装核心 | 五个远古版、a1.1.2_01、1.7.10、1.21.1 | 五项轻量安装从空缓存校验并落盘；完整准备 a1.1.2_01 的游戏、7 个类库及 749 项资源；安装 1.7.10 服务端文件、1.21.1 Fabric 和 Quilt 加载器。后两种加载器没有进入现代游戏主循环。[安装测试](tests/installer/RESULTS.md) |
| Android 6.0 x86，应用沙箱 | Java 8、运行环境 TLS | 实际 UID 内加载 APK 的宿主，校验 JRE、创建 JVM；对 GitHub 和发行文件域名验证证书并读取真实下载流。[运行环境测试](runtime/tests/RESULTS.md) |
| Android 6.0 x86，原生宿主 | Java 17、21 | 实际创建两套对应 HotSpot，并核对各自 java.specification.version。该项仅测试 JVM 初始化。 |
| Android 7.0 ARM64，原生宿主 | Java 25 | 使用 NDK r24 的 libc++ 时 JVM 返回 25 并正常退出。最终 APK 内的配套标准库来自 r28c，已核对文件摘要和 ELF 对齐，这个最终组合仍待运行复测。 |
| Android 6.0 x86，独立服务端子进程 | Vanilla 1.12.2 / Java 8 | 启动至 EULA 确认，实际生成配置，正常退出。未代用户接受 EULA，未测服务器在线游戏。 |
| Windows x64，JDK 17 | Fabric 1.20.1、Purpur 1.20.1；另校验 Purpur 1.21.1 | 前两者读取原版服务端并到达 EULA 阶段；Purpur 1.21.1 核对构建和文件摘要。[发行版矩阵](tests/server/RESULTS.md) |

## 文件、平台接口及用户操作

| 检查 | 证据与边界 |
| --- | --- |
| Modrinth、服务器识别、整合包 | 真实资源平台查询、Sodium JAR 下载及 SHA-512 校验、公开 .mrpack 下载与索引读取。夹具覆盖 Java 版状态握手、SRV 解析、递归依赖、错误版本拒绝、覆盖文件优先级、服务器整合包并发、失败回滚及重复安装。[网络及整合包测试](tests/network/test-results.txt) |
| 下载 | HTTPS、并发资源任务、复用、HTTP 206 续传、忽略 Range 后重下、完整性拒绝及重试。校验范围因上游而异，主缓存使用 Mojang SHA-1，服务器整合包使用 SHA-256，Modrinth 资源验证上游声明的摘要。 |
| FCL 布局与输入 | 使用上游 FCL v21 默认布局解析出 12 个可用控件，跳过 1 个非按键控件。布局往返保留未知字段。测试了选定的 options.txt 绑定生成、重复生成保护、组合输入与键值映射。[布局测试](tests/controls/test-results.txt) |
| 存档及实例 | 存档 ZIP 备份、同名另存恢复、非法路径拒绝、复制、移除与恢复、本地模组重复导入、远古世界轮换及坏备份保护。[本地文件测试](tests/local/results.txt) |
| 账户 | API 23 设备 Keystore 加解密、密钥不可导出、档案管理和凭据轮换通过。微软设备代码、Xbox、XSTS、权益与档案链使用受控响应。没有使用真实个人账户完成授权。[账户测试](tests/accounts/RESULTS.md) |
| 陶瓦联机组件 | Android 6.0 x86 上用本机协议测试服验证私人房间。上游原生状态达到 host-ok，包含有效联机码和已建立的系统 VPN；结束后状态回到 waiting，VPN 关闭。未发送邀请。[房间实测](tests/rooms/RESULTS.md) |

## 尚未完成或未验证

| 领域 | 当前边界 |
| --- | --- |
| 现代客户端和 Java | 现代正式版、快照和绝大多数历史版仍只支持相应文件准备；没有通用 LWJGL 3、OpenAL、AWT 窗口、完整启动参数适配、glibc 兼容层。Java 主版本取自元数据；清单提供的运行时组合不能直接推定游戏兼容性。 |
| 桌面图形 | 已有早期客户端的软件渲染路径，以及一套独立的 GLES 2 立即模式绘制子集。Vulkan 后端、完整桌面 OpenGL 1.x–4.6、着色器跨版本转换、驱动插件、自动降级、Sodium/Iris/OptiFine 和光影渲染尚缺。安装 Sodium 的测试仅验证资源下载。 |
| 输入 | 支持的 FCL v21 子集、虚拟键鼠、移动控件和物理键值映射可用。尚无通用运行时 KeyBinding 枚举；options.txt 无法覆盖未落盘的绑定。陀螺仪、完整手柄、所有 FCL 控件和通用游戏场景识别尚待实现。 |
| 资源和实例 | 已接入 Fabric、Quilt、Modrinth。Forge、NeoForge、LiteLoader、OptiFine、Cleanroom、CurseForge、MultiMC/HMCL 包导入和 FCL/PCL 实例迁移未完成。模组版本锁定/更新回滚、Modrinth 批量更新及全部地图格式未完成。 |
| 联机 | 标准状态协议只报告服主公开的信息，部分服务器需手动导入整合包。可选择自有 HTTPS 清单准备服务器专用实例。尚无 AutoModpack、全自动现代客户端入服、房主向访客同步环境和完整好友系统。跨运营商 P2P、独立中继切换和双机互通未做实测。 |
| 服务端 | 支持原版、Fabric 和固定构建的 Purpur。未完成 Paper、Spigot、Forge、NeoForge 的独立下载和安装；专用插件管理界面、定时备份与自动重启、TLS/权限环境变化下的长期前台保活、TPS/MSPT、双实例负载等尚未验收。 |
| 下载、诊断与扩展 | 下载器按内容校验，部分资源走并行或续传；尚无跨进程持久下载队列、统一暂停恢复面板、多源测速、通用底层驱动更新协议。错误分类限于可识别的日志片段；文件修复与图形能力检测已有入口。 |
| 账户、外观、硬件 | 尚无第三方认证和皮肤/披风管理。未测真实微软账户的完整在线多人流程。没有物理手机、多厂商 GPU、功耗、温度曲线、16 KiB 页设备或长期使用数据。模拟器帧率不能作为手机性能指标。 |

主包与房间组件均已验证版本名 Alpha1.0.0、版本代码 2、最低 API 23 和四种原生 ABI。两个 APK 的 v1/v2 调试签名验证均通过。主包四个 Activity 及房间 Activity 的最终清单声明均为 sensorLandscape，可随两种横握方向旋转。Android 6 x86 已从主包实际启动横屏双栏首页、点击进入远古游戏并通过触摸转动视角。[横屏首页](tests/app/home-api23.png) · [APK 验证输出](tests/app/apk-verification.txt)

完整工程位于本文件同目录，安装包、源码归档及 SHA-256 清单位于 dist。
