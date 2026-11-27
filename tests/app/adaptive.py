"""Exercise the installed launcher's navigation and appearance on an explicitly selected emulator."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--screens", type=Path, required=True)
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("Select a disposable Android emulator; this test changes its display and app preferences.")
device = [args.adb, "-s", args.serial]
package = "dev.lumencraft.android"


def adb(*command, timeout=55):
    return subprocess.check_output([*device, *command], timeout=timeout)


def shell(*command):
    return adb("shell", *map(str, command)).decode("utf-8", errors="replace").strip()


def snapshot():
    path = "/data/local/tmp/lumencraft-adaptive.xml"
    output = shell("uiautomator", "dump", path)
    if "dumped" not in output:
        raise RuntimeError(output)
    return ET.fromstring(adb("exec-out", "cat", path))


def locate(root, name, nav=False):
    field = "content-desc" if nav else "text"
    return next((node for node in root.iter("node") if node.get(field) == name), None)


def wait(name, nav=False):
    for _ in range(4):
        try:
            root = snapshot()
        except (ET.ParseError, RuntimeError):
            time.sleep(.4)
            continue
        node = locate(root, name, nav)
        if node is not None:
            return root, node
    raise AssertionError(f"Missing {'tab' if nav else 'text'} {name}")


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap(name, nav=False):
    _, node = wait(name, nav)
    x, y, right, bottom = bounds(node)
    shell("input", "tap", (x + right) // 2, (y + bottom) // 2)


def screen(name):
    args.screens.mkdir(parents=True, exist_ok=True)
    (args.screens / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))


def rotate(rotation):
    shell("settings", "put", "system", "accelerometer_rotation", "0")
    shell("settings", "put", "system", "user_rotation", rotation)
    time.sleep(1)


def restore_setting(name, value):
    if value == "null":
        shell("settings", "delete", "system", name)
    else:
        shell("settings", "put", "system", name, value)


old_rotate = shell("settings", "get", "system", "accelerometer_rotation")
old_angle = shell("settings", "get", "system", "user_rotation")
old_size = re.search(r"Override size:\s*(\d+x\d+)", shell("wm", "size"))
old_density = re.search(r"Override density:\s*(\d+)", shell("wm", "density"))
try:
    shell("wm", "size", "480x800")
    shell("wm", "density", 160)
    rotate("0")
    shell("am", "start", "-W", "-n", package + "/dev.lumencraft.MainActivity")
    tap("游戏", True)
    tree, _ = wait("启动远古版")
    tabs = [bounds(locate(tree, title, True)) for title in ("游戏","资源","联机","服务端","设置")]
    assert all(b[1] == tabs[0][1] for b in tabs), tabs
    screen("portrait")
    tap("资源", True)
    wait("搜索 Modrinth")
    shell("input", "swipe", 3, 260, 355, 260, 250)
    wait("启动远古版")
    print("PASS portrait bottom navigation and edge swipe back", flush=True)
    tap("服务端", True)
    wait("本地服务端")
    tap("联机", True)
    wait("陶瓦联机房间")
    tap("设置", True)
    wait("展开运行设置")
    tap("展开运行设置")
    wait("收起运行设置")
    tap("收起运行设置")
    tap("界面主题")
    tap("浅色")
    wait("浅色")
    tap("界面主题")
    tap("深色")
    wait("深色")
    screen("dark-settings")
    tap("界面动画")
    tap("关闭")
    wait("关闭")
    shell("input", "keyevent", "4")
    wait("陶瓦联机房间")
    print("PASS collapsible settings, explicit dark theme, motion switch and system back", flush=True)
    tap("设置", True)
    tap("界面主题")
    tap("跟随系统")
    tap("界面动画")
    tap("自动")
    tap("游戏", True)
    rotate("1")
    tree, _ = wait("启动远古版")
    tabs = [bounds(locate(tree, title, True)) for title in ("游戏","资源","联机","服务端","设置")]
    assert all(b[0] == tabs[0][0] for b in tabs), tabs
    screen("landscape")
    shell("wm", "size", "800x1280")
    time.sleep(1)
    tree, launch = wait("启动远古版")
    empty = locate(tree, "还没有游戏实例")
    titles = [n for n in tree.iter("node") if n.get("text", "").startswith("已安装")]
    assert titles and bounds(titles[0])[0] >= bounds(launch)[2], "Expected split instance layout"
    screen("tablet")
    print("PASS landscape sidebar and 1280x800 tablet split layout", flush=True)
finally:
    shell("wm", "size", old_size.group(1) if old_size else "reset")
    shell("wm", "density", old_density.group(1) if old_density else "reset")
    restore_setting("user_rotation", old_angle)
    restore_setting("accelerometer_rotation", old_rotate)
