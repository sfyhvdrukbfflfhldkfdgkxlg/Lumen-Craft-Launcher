# 微光 · 陶瓦联机伴侣

本模块单独构建和安装，应用 ID 为 `dev.lumencraft.rooms`。运行 `:rooms:assembleDebug` 会生成可安装 APK。主启动器可以显式打开 `dev.lumencraft.rooms.TerracottaActivity`。首次启动房间时，Android 会显示 VPN 授权界面。

## 创建与加入

- 有已经开放到局域网的本机 Minecraft 世界时，端口留空，选择「创建私人房间」。
- 分享本机独立 Java 服务器时，先启动服务端，再填写端口。伴侣会探测回环端口的游戏协议状态，并向本机扫描器通告这个端口。
- 显示「房间已就绪」后可以复制联机码。好友输入联机码后加入。访客可以复制陶瓦返回的本机连接地址，再在 Minecraft 中连接。
- 前台服务维持已启动的房间。点「结束联机」或在 Android 系统中撤销 VPN 可关闭会话。网络路径、直连和中继由上游 EasyTier 决定。

## 源代码与许可

房间伴侣的原创代码以 GNU AGPLv3 授权。上游 Terracotta 采用 AGPLv3，EasyTier 采用 LGPLv3。许可正文随 APK 放在 `assets/licenses`，在应用中也可查看。对应原生源码及其构建工作流位于 `upstream`，项目未引入 HMCL 启动框架。

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

Android 6 的 Bionic 缺少 `getifaddrs` 和 `freeifaddrs`。伴侣在 API 23 上加载 `libroomnet23.so`，通过 RTM_GETADDR 枚举 IPv4 与 IPv6 地址；API 24 及以上使用系统接口。Windows 可使用 `rooms/native/build.ps1 -Ndk <NDK目录>` 重建兼容库。NDK r24 已实测能够生成四个 ABI。

## 数据与功能边界

房间信息保留在内存中，输入框不保存联机码。主动复制时通过系统剪贴板传递，支持敏感标记的 Android 版本会附带该标记。上游本地机器标识位于应用私有的 noBackup 目录。本应用将上游文件日志指向 /dev/null。上游原生发布库仍会向系统 logcat 写运行诊断，调试设备上的日志可能包含联机码，分享系统日志前应先脱敏。

仅为本应用及微光启动器配置网段路由，不配置全局默认路由和公共 DNS。创建与加入需要网络能访问上游使用的发现与中继基础设施。房间成员身份以联机码为凭据。当前未实现跨设备自动安装玩家共享的模组环境、好友名单和跨进程重启后的房间恢复。

