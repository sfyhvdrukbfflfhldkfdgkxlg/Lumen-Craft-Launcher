"""Capture or tap an actual launcher screen on an explicitly selected adb device."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import time

p=argparse.ArgumentParser()
p.add_argument("--adb", default="adb")
p.add_argument("--serial", required=True)
p.add_argument("--tap")
p.add_argument("--capture",type=Path)
args=p.parse_args()
adb=[args.adb,"-s",args.serial]
xml="/data/local/tmp/lumen-ui.xml"
subprocess.run(adb+["shell","uiautomator","dump",xml],check=True,stdout=subprocess.DEVNULL,timeout=45)
data=ET.fromstring(subprocess.check_output(adb+["exec-out","cat",xml],timeout=10))
items=[(n.get("text"),n.get("bounds")) for n in data.iter("node") if n.get("text")]
if args.tap:
    matches=[bounds for text,bounds in items if text==args.tap]
    if len(matches)!=1:raise SystemExit(f"Expected one match for {args.tap!r}, got {len(matches)}")
    x,y,x1,y1=map(int,re.findall(r"\d+",matches[0]))
    subprocess.run(adb+["shell","input","tap",str((x+x1)//2),str((y+y1)//2)],check=True)
else:
    for text,bounds in items:print(text,bounds)
if args.capture:
    args.capture.parent.mkdir(exist_ok=True,parents=True)
    args.capture.write_bytes(subprocess.check_output(adb+["exec-out","screencap","-p"],timeout=25))
