# 微光 · 内置陶瓦联机

从 Alpha1.1.0 起，房间页面、VPN 前台服务、JNI 及四种 ABI 的原生库均编入应用 `dev.lumencraft.android`。运行 `:app:assembleDebug` 构建主包。在启动器的「联机」页打开「陶瓦联机房间」。首次启动房间时，Android 会请求 VPN 授权。

## 创建与加入

- 有已经开放到局域网的本机 Minecraft 世界时，端口留空，选择「创建私人房间」。
- 分享启动器管理的独立 Java 服务器时，先启动服务端。留空端口可读取当前服务端的 `server-port`，其他本机服务可手动填写端口。房间服务探测回环端口的 Minecraft 状态协议，并向本机扫描器通告这个端口。
- 房间就绪后可复制联机码，或点「分享房间邀请」并选择要附上的客户端实例。附加环境记录游戏和加载器的精确版本，以及可从 Modrinth 校验的启用模组。访客粘贴完整邀请、确认准备环境后，启动器会校验模组及必要前置并连接房间。只持有原生联机码的访客可直接加入。
- 前台服务维持房间。上游报告可恢复的连接或游戏心跳故障时，最多尝试五次重连，退避时间依次为 2、4、8、16、32 秒。点「结束联机」或撤销 VPN 授权可结束会话。直连、中继及链路选择由上游 EasyTier 处理。

## 源代码与许可

集成后的启动器按仓库根目录的 GNU AGPLv3 许可分发。上游 Terracotta 采用 AGPLv3，EasyTier 采用 LGPLv3。许可正文随 APK 放在 `assets/licenses`，房间页面提供许可证及对应源码入口。原生源码归档及其上游构建工作流位于 `upstream`。

| 组件 | 对应源码 | 归档 SHA-256 |
| --- | --- | --- |
| Terracotta v0.4.2 | [70d5421](https://github.com/burningtnt/Terracotta/tree/70d542156377b316659b4ec2ac62341a43ddc4b2) | `55431a067c4273a2f5d23eee4d9b0f2ca5c5b0ec61bd707d4eeaa704228d3ae5` |
| EasyTier v2.5.0-terracotta.2 | [66c82d0](https://github.com/burningtnt/EasyTier/tree/66c82d0a99ae6733463e37eaa3b8c91f584c4689) | `415e371c8397720a05ab6348c9e1322304628334c469ab458f5cf7f11a3689ab` |

`NativeRooms.java` 按上游动态 JNI 注册表接入。Terracotta 的 Android 发布库已经包含 EasyTier。四个 ELF 使用[上游发布文件](https://github.com/burningtnt/Terracotta/releases/tag/v0.4.2)，下载后逐一核对 SHA-256。构建上游源码时将 Rust 依赖中的 EasyTier Git 版本固定到上表的提交；上游标签里的 Cargo.toml 仍然追踪分支。

| ABI | libterracotta.so 的 SHA-256 |
| --- | --- |
| arm64-v8a | `fc426710de5f53ae5b6350fdffe1012082992dac5b9d93ea5e86c0e56af5567a` |
| armeabi-v7a | `a777504e66bff55df4774d953598e33211a05b782fff4a5fd2a5dba254474239` |
| x86 | `45df60fe08e9d37ac1eab720eb494405162ff911b1b8d54ec3342c041e691722` |
| x86_64 | `93f248637a966c8d3bb84d06ed027ad9ba7347615b3bab92e7d38b8764d023e4` |

Android 6 的 Bionic 缺少 `getifaddrs` 和 `freeifaddrs`。启动器在 API 23 上加载 `libroomnet23.so`，通过 RTM_GETADDR 枚举 IPv4 与 IPv6 地址。API 24 及以上使用系统接口。Windows 可使用 `rooms/native/build.ps1 -Ndk <NDK目录>` 重建兼容库。NDK r24 已实测能够生成四个 ABI。

## 数据与功能边界

房间信息保留在内存中，输入框不保存联机码。主动复制时通过系统剪贴板传递，支持敏感标记的 Android 版本会附带该标记。上游本地机器标识位于应用私有的 noBackup 目录。本应用将上游文件日志指向 /dev/null。上游原生发布库仍会向系统 logcat 写运行诊断，调试设备上的日志可能包含联机码，分享系统日志前应先脱敏。

VPN 仅接收启动器 UID 的房间网段流量，该 UID 包含启动器管理的客户端和服务端 JVM。创建与加入需要网络能访问上游使用的发现与中继设施。联机码是房间凭据。持久好友名单、Android 杀进程后的会话恢复和现代 Minecraft 客户端自动入服尚待实现。

