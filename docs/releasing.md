# 构建与发布

版本名和 Android versionCode 统一保存在根目录 release.properties。每个版本提高 versionCode 并使用新的版本名。Alpha、Beta 和 RC 前缀触发 GitHub Pre-release，稳定版本可使用 1.2.3 或 v1.2.3。已发标签与下载文件保留原值。

## 本地构建

Windows 需要 JDK 17、Python 3.11+、Android SDK 35 和 Build Tools 35.0.0。设置 JAVA_HOME 与 ANDROID_HOME 后在工程根执行。工具会保留本机已有的 Gradle 缓存。

```powershell
.\build.ps1                            # 开发调试包
.\build.ps1 -Release -Unsigned         # 编译通用和四个 ABI 的 Release APK，执行主机回归
.\build.ps1 -Release -UseInstalledAlphaKey
python tools/package.py verify
```

第三条命令适用于仍持有 Alpha1.0.0 签名密钥的原工作站。构建使用 release variant，在 Gradle 完成后通过 apksigner 签名。验证器固定匹配 Alpha1.0.0 的证书 SHA-256（517e9aaddda76fe7abfc9dd9b64b619de8cbbf5f37235f2036d0210fd56cb0a8）。其他电脑自动生成的 Android 调试证书会被拒绝。应用 ID 与各 ABI 的 versionCode 保持一致，可从旧通用包升级。

签名后的五个 APK 和 SHA256SUMS.txt 位于 dist/版本名/。通用包支持四种 ABI，每个独立包只包含其目标 ABI。旧 dist 下的 Alpha1.0.0 文件归属于上一版。单独运行 Python 验证时也需要设置 ANDROID_HOME。

## GitHub Actions

所有 PR 和 main 推送先编译五个 Release APK 并运行本地主机回归。发布支持两条路径。

1. 在持有原密钥的机器完成签名，将 dist/版本名/ 中六个已验证文件与对应源码一同提交到 main。如果使用 git CLI，dist 目录受忽略规则保护，这次需要对六个指定文件使用 git add -f。新版本候选文件会触发发布，私钥留在本地。
2. 配置仓库 Actions Secrets 后，可以从 main 手动运行 Android build and release，或推送与 versionName 一致的新 Git Tag。工作流接收 LUMEN_KEYSTORE_BASE64、LUMEN_KEY_ALIAS、LUMEN_STORE_PASSWORD 与可选的 LUMEN_KEY_PASSWORD；最后一项缺省时使用 keystore 密码。密钥解码到签名进程的临时目录，构建、测试、发布步骤各自有独立的凭据范围。也支持在本机设置 LUMEN_KEYSTORE_PATH 代替 base64。任何凭据均不得写入仓库。

对离线签名文件，CI 比较独立重编得到的 APK 中每个非签名 ZIP 条目的内容摘要和压缩方式，拒绝重复条目，并检查包名、版本、ABI、集成组网原生库、签名、对齐及 SHA-256。容器时间戳和签名块不参与重编比较。证书、v1/v2 签名和安装验证对实际分发的 APK 另外执行。Git checkout 保留上游资源的行尾，预编译原生文件保留原始内容。

发布前使用 Android 10 x86_64 模拟器先安装固定摘要的 Alpha1.0.0，再用 adb install -r 覆盖升级至候选通用包，分别在竖屏与横屏启动实际界面。需有可用的 GitHub 托管 runner、Actions 执行额度与 contents:write 发布权限。模拟器或测试失败时工作流不会公开 Release。

发布作业创建新 tag，根据 Git 历史和可选 docs/releases/版本名.md 生成日志，将五个 APK 和 SHA256SUMS.txt 上传到草稿。重新下载并核验 GitHub 上的六个附件后才公开。草稿发布失败后可在原运行记录点 Re-run jobs。工作流会识别指向原提交的标签，复核已上传的文件并补传缺失文件。GitHub 将中断上传标记成零字节 starter 时，工作流会清理草稿中的空条目再续传。已上传文件摘要不符时发布会停止。[GitHub Release 附件状态](https://docs.github.com/en/rest/releases/assets)

GitHub 工作流位于 .github/workflows/android-release.yml。Actions 控制台中的完成状态、公开 Release 页面和其中的 SHA256SUMS.txt 共同记录远端交付结果。[GitHub Releases CLI](https://cli.github.com/manual/gh_release_create) · [Android 多 ABI APK](https://developer.android.com/build/configure-apk-splits)
