# RubyDung 客户端运行桥

此桥根据官方 JAR 的 SHA-1 选择入口，在独立 OpenJDK 进程内调用游戏原有的 `main`。APK 中包含本项目的桥和软件光栅器。官方游戏文件在用户安装指定版本时获取。支持清单如下。

| Mojang 版本 ID | 已识别的官方客户端 SHA-1 |
| --- | --- |
| rd-132211 | 393e8d4b4d708587e2accd7c5221db65365e1075 |
| rd-132328 | 12dace5a458617d3f90337a7ebde86c0593a6899 |
| rd-160052 | b100be8097195b6c9112046dc6a80d326c8df839 |
| rd-161348、rd-20090515 | 6323bd14ed7f83852e17ebc8ec418e55c97ddfe4 |

`java/org/lwjgl` 实现这些 JAR 实际调用的 LWJGL 2 方法。固定管线、深度、雾、纹理、显示列表、选择缓冲和拾取交给 OSMesa。独立实现 mipmap 金字塔、透视和拾取矩阵。320 × 240 单上下文在游戏线程渲染，帧率上限 30。窗口将 RGB 表面显示为不透明，局部 TCP 会话由随机凭据绑定到 Android 控制页，输入队列传递按下、释放和相对移动。当前协议每帧传递完整的 RGBA 缓冲。此组历史版本本身没有现代游戏的光影、模组加载及音频需求。

## 第三方来源与许可

手机切入后台时会暂停提交游戏帧。系统省电、Android 标记的低内存设备或手动省电配置在下一次启动时将帧率上限降为 15。实际帧率由 CPU、后端和场景决定。

软件后端使用 Mesa 3.4.2 的 C 核心和 `src/OSmesa/osmesa.c`，按上游 XFree86 风格的许可使用。归属、授权全文与版权声明随 APK 一并分发，在项目内见 [Mesa-3.4.2.txt](../app/src/main/assets/licenses/Mesa-3.4.2.txt)。Mesa 3.4.2 的不同组件适用不同许可，构建脚本列出了实际编译的源文件。上游 GLU、GLX、窗口系统驱动和演示程序均未链接。本项目 JNI 和 Java 适配代码位于本目录。

构建输入取自 [Mesa 官方归档](https://archive.mesa3d.org/older-versions/3.x/MesaLib-3.4.2.tar.gz)。归档 SHA-256 为 `b02b5f77321175820b9955b07979d9f8c5d52e146eecc719844380ef2849ddd6`。脚本在解包前校验固定摘要，选择 Bionic 的可移植浮点代码路径，并修订旧 C 源中无法由现代 Clang 解析的诊断格式串。

## 重新构建

使用 Python 3.12、包含 `javac` 的 JDK 17 或更高版本和 Android NDK r24。Windows PowerShell 示例：

```powershell
python client/build.py --ndk $env:ANDROID_NDK_HOME
```

输出为 `app/src/main/assets/classic-bridge.jar` 和四个 ABI 的 `liblumenclassic.so`。渲染器以 API 23 为编译目标。桥只接受原版允许清单中的游戏文件，未实现完整 LWJGL 2/3 ABI 或通用桌面 OpenGL 4.6。

## 复现主机端测试

Windows x64 使用 TinyCC 0.9.27 与 Windows JDK 提供的 JNI 头生成同一桥的 PE 测试库。脚本为 LLP64 平台选用能保存指针宽度的对齐运算。测试库文件名与 Android 端一致，加载时由各自平台的 JVM 判别文件格式。为主机验证使用单独的临时构建目录。

```powershell
$work = Join-Path $env:TEMP 'lumen-classic-host'
python client/build.py --windows-test-cc C:/tools/tcc/tcc.exe --jdk $env:JAVA_HOME --work $work
python client/test_session.py --java "$env:JAVA_HOME/bin/java.exe" --bridge app/src/main/assets/classic-bridge.jar --native $work --game C:/games/rd-132211.jar --results "$work/results"
```

测试从未经修改的官方游戏收取真实帧，发送移动与转向输入，保存世界，向游戏发送拆除操作，然后退出。测试检查退出状态、渲染颜色、帧变化以及解压存档中恰好一个方块的变化。不同随机出生点会产生不同的帧哈希。证据图和 JSON 位于 `tests/classic-host`。
