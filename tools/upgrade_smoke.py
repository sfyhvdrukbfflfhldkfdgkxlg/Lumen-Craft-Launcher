"""Install the previous release, upgrade in place, and open the new UI in both orientations."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

BASELINE = "https://raw.githubusercontent.com/sfyhvdrukbfflfhldkfdgkxlg/Lumen-Craft-Launcher/8d47f99411dec8b4ffd743c0a2f7e59f2522d54d/dist/LumenCraft-Alpha1.0.0.apk"
BASELINE_HASH = "9ca8f681ad2892aaa2d3ef7d14cd34d2cd7a07cfae3349278169cad9413176ea"
PACKAGE = "dev.lumencraft.android"


def adb(*args):
    return subprocess.check_output(["adb", *map(str, args)], encoding="utf-8", errors="replace", timeout=90)


def smoke(apk, baseline):
    with baseline.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != BASELINE_HASH:
            raise ValueError("Incorrect Alpha1.0.0 baseline")
    print(adb("install", "-r", baseline))
    print(adb("install", "-r", apk))
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    for rotation in ("0", "1"):
        adb("shell", "settings", "put", "system", "user_rotation", rotation)
        adb("shell", "am", "force-stop", PACKAGE)
        output = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/dev.lumencraft.MainActivity")
        if "Status: ok" not in output:
            raise RuntimeError(output)
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                adb("shell", "uiautomator", "dump", "/data/local/tmp/lumencraft-ui.xml")
                xml = ET.fromstring(adb("shell", "cat", "/data/local/tmp/lumencraft-ui.xml"))
                nodes = [node for node in xml.iter("node") if node.get("package") == PACKAGE]
                labels = [node.get(k, "") for node in nodes for k in ("text", "content-desc")]
                if nodes:
                    bounds = re.findall(r"\d+", nodes[0].get("bounds", ""))
                    right, bottom = map(int, bounds[2:])
                    correct_orientation = right > bottom if rotation == "1" else bottom > right
                    if correct_orientation and all(label in labels for label in ("游戏", "资源", "联机", "服务端", "设置")):
                        print(f"Verified app UI: rotation {rotation}, bounds {right} x {bottom}")
                        break
            except (subprocess.SubprocessError, ET.ParseError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Launcher UI did not become accessible in rotation " + rotation)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as work:
        baseline = args.baseline
        if baseline is None:
            baseline = Path(work) / "baseline.apk"
            urllib.request.urlretrieve(BASELINE, baseline)
        smoke(args.apk, baseline)
