# 陶瓦联机实测

测试日期为 2026-11-26。使用 Android SDK x86 模拟器、Android 6.0、API 23、Terracotta v0.4.2 和 EasyTier v2.5.0-terracotta.2。系统授权 VPN 后，运行独立联机伴侣。

| 检查 | 实际结果 |
| --- | --- |
| 原生库加载 | 四个上游 ABI 文件核对 SHA-256，x86 在设备进程实际加载并返回 Terracotta / EasyTier 版本 |
| API 23 兼容 | 原始二进制强引用系统尚未提供的两个网络接口枚举函数。补充模块在模拟器实际获得 1 个回环与 1 个其他活动 IPv4 接口 |
| 服务端端口 | 通过 adb reverse 将实例的 25926 映射到测试机回环状态服务，成功完成 Java SLP 与 Ping/Pong。有意使用固定测试内容，未运行完整游戏世界 |
| 房间与 VPN | 启动本机服务端端口共享；原生状态进入 `host-ok`，再次读取相隔超过两分钟仍为 `host-ok`。联机码存在，Android VpnService 的 TUN 文件描述符已建立 |
| 关闭 | 结束房间后为 `waiting`，错误字段为空、联机码已清空、VPN 描述符已关闭，测试端口转发已移除 |
| 负面用例 | 只支持现代状态协议的测试服务触发陶瓦上游的 0xFE 心跳失败，状态为类型 4。补全测试服务的真实旧版 0xFF 状态响应后房间稳定 |
| 启动界面 | 在无硬件加速的模拟器与游戏 JVM 并行运行时，伴侣冷启动曾收到系统 ANR 对话框。随后成功继续，后台开展了房间测试 |

本次无人加入测试房间。尚未在第二台设备上验证好友加入、P2P 直连、中继、跨网络重连、实际游戏会话和 ARM 设备。

## 重复关键检查

1. 按 `rooms/native/build.ps1` 重建 API 23 兼容库，执行 Gradle 的 `:rooms:assembleDebug`，将生成的 APK 安装到测试设备。
2. 在主机运行 `python tests/rooms/loopback_status.py`，用 `adb -s <serial> reverse tcp:25926 tcp:25926` 转发测试服务。
3. 在伴侣输入本机端口 25926 后创建私人房间，接受系统 VPN 授权。用户触发的正常流程会启动 `RoomService`。本次回归也在授权后通过测试设备的 `am startservice` 调用该服务，验证它向原生核心提交并跟踪真实房间。
4. 在可调试 APK 上，可用 adb 的 JDWP 端口转发到伴侣进程，将本目录的 `ReadRoomState.java` 用 JDK 编译并传入转发端口；工具仅输出状态、核心版本、错误、联机码是否存在和 VPN 描述符是否存在。
5. 结束联机，确认房间状态及 VPN 资源已释放。关闭测试服务并删除对应 adb reverse 映射。

`interfaces23_probe.c` 可以与 `rooms/native/interfaces23.c` 一起编译为测试可执行文件，检验旧 Android 对网络接口枚举的返回值、IPv4 接口标志和地址族。


## 横屏构建

联机伴侣入口使用 sensorLandscape，支持左右两个横屏方向。可用宽度至少 560 dp 时，左栏展示房间状态和版本，右栏提供建房、加入和会话操作。两栏可以独立滚动。窄窗口使用整体可滚动的单列布局，并启用软键盘 adjustResize。

已重新构建 rooms:assembleDebug，使用 aapt2 从最终 APK 的编译清单读回 screenOrientation 数值 6，并通过 apksigner 在 API 23 下的 v1/v2 签名校验。该次界面调整未再启动模拟器。上文的组网实测来自使用相同服务实现的前一构建。
