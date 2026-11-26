# Android 客户端运行验收

2026-11-26，Android 6.0 / API 23，Android Emulator 的 x86 软件 CPU，系统指纹 `Android/sdk_phone_x86/generic_x86:6.0/MASTER/4174734:userdebug/test-keys`。使用应用构建出的 x86 `liblumenjvm.so`、`liblumenclassic.so`、`classic-bridge.jar`，以及目录中提供的 Android OpenJDK 8 x86 发行包。游戏为下载并校验的 Mojang 原版 `rd-132211`。

| 检查 | 运行结果 |
| --- | --- |
| OpenJDK 和原始入口 | JNI 创建 Java 8 运行环境，RubyDung 进入原始游戏主循环 |
| 纹理与几何 | 从官方客户端内的 PNG 经 ImageIO 解码至软件光栅器，共收到 156 帧 320 × 240 RGBA |
| 输入 | 通过应用的实际会话协议传入按键与鼠标事件。两张证据图记录了视角改变前后的真实画面 |
| 世界交互 | 游戏自己的拾取和拆除逻辑改变一个方块。用游戏保存指令和退出保存的两份 level.dat 比较其解压字节，恰好一处不同 |
| 退出 | 子进程退出码 0，世界保存成功 |

本次由 adb 启动 APK 所使用的运行核心，桌面测试器经 adb reverse 连接 Android 上的窗口协议。屏幕显示、控件命中及 Activity 生命周期另属 APK 界面测试。软件 CPU 的帧率不能代表手机硬件。该测试覆盖 x86 上的 API 23、Java 8 与单个游戏版本。

读 [session.json](session.json)、[输入前画面](frame-0.png)、[输入后画面](frame-20.png) 和 [原始游戏日志](game.log)。首次启动时原版游戏尝试加载 level.dat，会打印找不到该文件的信息，随后生成并保存世界。

## 复测

在专用 x86 测试设备的 `/data/local/tmp/lumencraft-qa/java8` 放入同一 JRE 的完整 Java home，将 APK 的 x86 宿主放入 `/data/local/tmp/lumencraft-qa/liblumenjvm.so` 并赋予执行权限。以已校验的原版 JAR 运行如下命令。每次测试使用新的临时世界目录。

```powershell
python client/test_session.py --adb adb --serial emulator-5576 --bridge app/src/main/assets/classic-bridge.jar --native app/src/main/jniLibs/x86 --game C:/games/rd-132211.jar --results C:/temp/lumen-frame-test
```
