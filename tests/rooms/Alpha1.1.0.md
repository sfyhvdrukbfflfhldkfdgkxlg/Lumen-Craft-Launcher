# Alpha1.1.0 内置陶瓦设备测试

本轮使用独立 Android SDK x86 模拟器（Android 6.0，API 23）和包名为 `dev.lumencraft.android` 的 Alpha1.1.0 调试构建。设备检查通过 JDWP 读取与显式操作对应的状态，只记录是否存在联机码和 VPN 描述符。调试包的 SHA-256 为 `b2d334191e65c81e0fb5dc8a7af3f32cd20d8ff4b910f12004d05aa6886f775d`。

## 实测方法

1. 在宿主运行本目录的 `loopback_status.py`，通过专用测试设备的 `adb reverse tcp:25926 tcp:25926` 提供现代及旧版 Minecraft 状态响应。
2. 从启动器主界面打开「联机 → 陶瓦联机房间」，在手动端口中输入 25926 并创建房间。首次请求 VPN 时使用 Android 系统授权界面。
3. 使用 `ReadRoomState.java` 读取内置原生核心状态、联机码存在标志及 VPN 文件描述符存在标志。通过房间页面结束联机，再检查对应资源释放。

## 2026-11-27 设备读回

| 操作 | 观测 |
| --- | --- |
| 打开房间、首次创建 | 从启动器「联机」页进入同包的 `dev.lumencraft.rooms.TerracottaActivity`。15:20:35 接受 Android VPN 授权。原生版本为 Terracotta 0.4.2、EasyTier v2.5.0-terracotta.2。 |
| 建房 | 15:20:39 为 `initializing`，15:20:43 为 `host-starting`，15:20:47 为 `host-ok`。联机码存在标志和 VPN 描述符存在标志均为 true，错误字段为空。 |
| 结束及回收 | 15:21:50 为 `closing`，联机码已清空，此时 VPN 描述符仍存在。15:21:55 回到 `waiting`，两项存在标志均为 false，错误字段为空。之后检查设备，`ip link show tun0` 返回设备不存在，测试端口的 adb 反向转发也已移除。 |

## 范围

测试端口运行 Minecraft 状态协议响应夹具。本轮覆盖房主和隧道生命周期。访客加入、双机跨运营商连接、实际游戏世界联机、断网重连和硬件性能尚待单独验收。软件模拟器启动界面曾出现系统无响应对话框，选择等待后继续完成建房。
