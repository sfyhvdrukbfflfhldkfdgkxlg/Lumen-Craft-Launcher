# 服务端安装测试记录

2026-11-26 在 Windows x64 上运行真实的 ServerDistributions 入口，下载文件并读取生成的启动 JAR。随后在各自实例目录使用 Temurin JDK 17.0.20.1 运行 java -Xmx384m -jar server.jar nogui，保留首次运行时 EULA 的默认值。

| 发行版 | 下载和安装 | 桌面首次启动结果 |
| --- | --- | --- |
| Minecraft 1.20.1 + Fabric Loader 0.20.1 | 官方版本元数据、Mojang server.jar、8 个 Fabric Maven 文件完整性校验通过，清单中的每个类路径文件存在 | 识别 Minecraft 与 Fabric，进入 Minecraft EULA 确认后退出 |
| Purpur 1.20.1，构建 2062 | 官方成功构建记录匹配，SHA-256 238e64b33e5c7c9b87506cf1a23f239e6ce7fb71edd9d31837f229c94f4dfa1a，46,903,164 字节 | Paperclip 完成补丁，进入 CraftBukkit 入口和 Minecraft EULA 确认后退出 |
| Purpur 1.21.1，构建 2329 | 已从发布地址下载并核对官方 MD5 与固定 SHA-256 30403cf54f981f16e1403f172645e82d3e4a59ad6c9f1d8e98df99edb1f8ae4c，53,071,332 字节 | 未运行 |

复现安装可运行 tests/server/run.ps1 -Distribution fabric -MinecraftVersion 1.20.1。将 fabric 改为 purpur 即可复现另一项。运行时依赖及许可证由各自上游提供，应用只保留下载地址与构建固定信息。

这组测试没有创建世界、连接玩家或加载第三方插件。Android 设备结果以项目总测试报告和 runtime 测试记录为准。
