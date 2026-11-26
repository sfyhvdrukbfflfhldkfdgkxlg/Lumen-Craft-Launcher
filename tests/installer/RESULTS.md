# 原版文件与加载器安装测试

2026-11-26，Windows x86-64、Oracle JDK 25.0.4.1，核心代码按 Java 8 编译。Android 运行时使用平台的 `org.json`，主机回归使用 checksum 固定的 JSON-java 20240303。

| 范围 | 输入 | 实测结果 |
| --- | --- | --- |
| Mojang 官方版本清单 | `version_manifest_v2.json` | 从 HTTPS 获取并解析 926 条记录 |
| 原版客户端完整文件准备 | `a1.1.2_01` | 主机下载、逐个校验客户端、7 个 Java 库及 749 项资源，写入实例收据 |
| 原版服务端文件准备 | `1.7.10` | 下载并校验 9,605,030 字节官方服务端，SHA-1 为 `952438ac4e01b4d115c5fc38f891710c4941df29` |
| Fabric 客户端组件 | 游戏 1.21.1，稳定版自动选择 | 独立测试实例取到官方 0.20.1 profile，按摘要验证所需各库 |
| Quilt 客户端组件 | 游戏 1.21.1，稳定版自动选择 | 独立测试实例取到官方 0.30.2 profile，按摘要验证所需各库 |
| 可复现本地回归 | 固定 HTTP 字节及文件夹夹具 | SHA-1/256、HTTP 206 续传、服务器返回 200 后从头下载、损坏缓存修复、摘要不符拒绝提交、拒绝 HTTP、线程取消、路径非法、Linux 库条件、共享缓存、历史资源目录、实例隔离、无匹配版本、加载器锁版及 Maven 校验通过 |

上述客户端和加载器测试覆盖文件准备。它们没有运行 a1.1.2_01、Fabric 或 Quilt 的游戏主循环。客户端原生库、桌面窗口和输入需要独立运行适配。原版安装要求官方清单提供精确版本、Java 主版本及必要文件摘要。仅下载 Java 库，跳过桌面平台专用原生分类器。未验证自定义版本继承。

用 PowerShell 运行离线夹具测试（首次会获取主机测试用 JSON 库）。

```powershell
.\tests\installer\run.ps1
```

运行真实官方网络用例时，测试文件会保存在 Windows 临时目录，最新版本清单与加载器稳定版本会随上游变化。

```powershell
.\tests\installer\run.ps1 -LiveCases @('client=a1.1.2_01','server=1.7.10','profile=fabric,1.21.1','profile=quilt,1.21.1')
```

官方入口见 [Mojang 版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)、[Fabric Meta](https://meta.fabricmc.net/) 与 [Quilt Meta](https://meta.quiltmc.org/)。

## 发布前复核

在空的临时安装目录执行 `run.ps1 -LiveCases @('classic=all')`。五个轻量安装入口均从官方端点下载元数据及客户端，逐个与实时清单中的 SHA-1、官方 JAR 长度、内容摘要和 Java 主版本比对，生成五个独立实例、四份唯一游戏 JAR。下列为主机实际输出。

```text
LIVE PASS rd-132211 manifest=d090f5d3766a28425316473d9ab6c37234d48b02 jar=393e8d4b4d708587e2accd7c5221db65365e1075 bytes=26704
LIVE PASS rd-132328 manifest=4ec49ff663f96e78a5cf0d9538adb9d1358fc485 jar=12dace5a458617d3f90337a7ebde86c0593a6899 bytes=35435
LIVE PASS rd-160052 manifest=0cac2ceab812568826c6e5aeb4cf980397550479 jar=b100be8097195b6c9112046dc6a80d326c8df839 bytes=49076
LIVE PASS rd-161348 manifest=f22a3882d124ef4468f6eb50b12836c53286e18a jar=6323bd14ed7f83852e17ebc8ec418e55c97ddfe4 bytes=60480
LIVE PASS rd-20090515 manifest=a3165080e2b0bf20519eac5f55ee841f3491e277 jar=6323bd14ed7f83852e17ebc8ec418e55c97ddfe4 bytes=60480
```

已验证中文整合包目录的完整性修复。原版安装夹具损坏客户端缓存后，通过原中文实例目录重新安装，返回正确内容且保留同一目录。
