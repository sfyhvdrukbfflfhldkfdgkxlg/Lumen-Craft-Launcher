# 账户与凭据测试

2026-11-26，Android Emulator x86，Android 6.0 / API 23。测试使用独立 Android 应用 UID、真实 Android Keystore 和私有 SharedPreferences。测试包通过 Android instrumentation 执行，返回 `INSTRUMENTATION_CODE: -1` 与 `result=PASS`。

| 检查 | 结果 |
| --- | --- |
| 离线档案 | 从 `OfflinePlayer:<名称>` 生成标准离线 UUID，添加、重复与非法名称拒绝、多账号切换、重新打开、删除及当前账号回退通过 |
| 实际凭据存储 | AES-256/GCM 加解密通过。Android Keystore 密钥的 `getEncoded()` 返回 null。磁盘保存的密文不包含测试玩家名及刷新凭据。篡改认证密文后读取失败 |
| 令牌轮换 | 新刷新凭据保存通过，使用过期凭据写回被拒绝 |
| 设备代码 | 注入内存响应，验证公共客户端 ID 格式、scope、pending 轮询、授权页域名校验与成功流转 |
| Xbox 与 Minecraft | 注入内存响应，检查 RPS 身份票据、XSTS 受众、Minecraft 身份票据、Bearer 请求、Java 权益及玩家档案。缺失权益时未创建在线身份 |

应用只保存在线账号的刷新凭据、所属公共客户端 ID 和玩家标识。MSA、Xbox、XSTS 和 Minecraft 短期访问令牌仅在内存中使用。Keystore 密钥是否有硬件保护由设备决定。移除账户将删除本机保存的身份和凭据，微软账户网站可管理远端授权。离线身份仅用于本地。现有 RubyDung 客户端没有正版多人服务器登录功能。

微软登录需要由使用方配置允许个人 Microsoft 帐户和公共客户端流程、且获准使用 Java 游戏服务的应用 ID。项目没有附带其他启动器的客户端 ID。此交付未使用个人账号进行真实授权、权益查询或刷新验证。Xbox 家庭设置、订阅权益表现和具体应用注册结果仍需持有者实测。相关说明见 [Microsoft 设备代码授权](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)、[Minecraft Java 游戏服务审核](https://help.minecraft.net/hc/en-us/articles/16254801392141-New-Java-Edition-Game-Service-API-Review-or-Application-Process) 和 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

PowerShell 复测命令如下。测试在其专用包中使用夹具身份，未向身份服务发送请求。

```powershell
.\tests\accounts\run.ps1 -Sdk $env:ANDROID_HOME -Jdk $env:JAVA_HOME -Device emulator-5576
```

## 横屏布局构建检查

2026-11-26，使用 JDK 17 的 Java 8 目标和 Android 35 SDK 编译横屏账户页、认证代码及测试，再以 D8 的 `--min-api 23` 生成 48,464 字节的 DEX，两个步骤均成功。账户页在可用宽度至少 440dp 的横屏窗口采用分别滚动的操作栏与列表，窄窗整体滚动。设备代码对话框的说明可滚动，官网与取消按钮位于按钮栏。此次横屏变更的验证范围是编译与 DEX 生成，设备上的软键盘、字体缩放和控件位置待实际验证。
