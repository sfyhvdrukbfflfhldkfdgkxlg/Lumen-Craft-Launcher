# 服务器客户端整合包接口

服主可以用静态 HTTPS 文件公布客户端整合包。联机页检测服务器后，选择“查找服务器整合包”。此接口使用自己的清单格式。AutoModpack 握手尚未接入。

在玩家输入的主机名下发布 `/.well-known/lumencraft/<游戏端口>.json`，默认游戏端口为 25565。HTTPS 使用站点的 443 端口。DNS SRV 记录用于游戏状态连接。清单始终从玩家输入的域名获取。自定义插件可以生成同样的 JSON 文件。

| 字段 | 内容 |
| --- | --- |
| schema | 整数 1 |
| port | 玩家输入的 Minecraft 端口 |
| minecraft | Mojang 官方清单中的精确版本 ID |
| modrinthPack.name | 展示给玩家的整合包名称 |
| modrinthPack.url | 该 .mrpack 文件的完整 HTTPS 下载地址 |
| modrinthPack.size | 整包实际字节数，上限 256 MiB |
| modrinthPack.sha256 | 整包 64 位十六进制 SHA-256 |

服务器状态报告了精确官方版本时，它必须与清单相符。玩家可以在安装前查看整合包来源、版本和大小。下载后会核对摘要和整合包内部 Minecraft 版本，再安装客户端必需的文件和所声明的加载器版本。

实例名称包含服务器地址的标识和归档摘要。再次安装同一整合包会复用已建实例。更换归档后会生成新实例，旧版本及其存档继续保留。当前整合包导入器处理原版、Fabric 和 Quilt，支持的文件来源及数据目录以 PackImport 实现为准。服主未发布清单时，游戏页支持手动导入 .mrpack。
