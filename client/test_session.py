"""Exercise the real game loop over the same authenticated frame and input protocol as Android."""
import argparse
import hashlib
import gzip
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import zlib

def png(path, width, height, pixels):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    image = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">2I5B", width,height,8,6,0,0,0)) +
                     chunk(b"IDAT", zlib.compress(image)) + chunk(b"IEND", b""))

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--java", default="java")
    p.add_argument("--adb", help="Use an already booted Android test device with an unpacked matching OpenJDK 8")
    p.add_argument("--serial")
    p.add_argument("--bridge", type=Path, required=True)
    p.add_argument("--game", type=Path, required=True)
    p.add_argument("--native", type=Path, required=True, help="directory containing host-ABI liblumenclassic.so")
    p.add_argument("--results", type=Path, required=True)
    args = p.parse_args()
    token = secrets.token_hex(20)
    args.results.mkdir(parents=True, exist_ok=True)
    with socket.socket() as listener, tempfile.TemporaryDirectory() as work:
        listener.bind(("127.0.0.1",0))
        listener.listen(1)
        listener.settimeout(150 if args.adb else 30)
        port = listener.getsockname()[1]
        command = [args.java,"-Djava.awt.headless=true","-XX:ErrorFile="+str((args.results/"jvm-error.log").resolve()),"-Xmx256m","-cp",
                   os.pathsep.join(str(f.resolve()) for f in [args.bridge,args.game]),
                   "dev.lumencraft.bridge.Session", str(port), token,str(args.native.resolve())]
        if args.adb:
            adb = [args.adb] + (["-s",args.serial] if args.serial else [])
            runtime = "/data/local/tmp/lumencraft-qa"
            remote = "/data/local/tmp/lumen-client-test-" + secrets.token_hex(5)
            home = runtime + "/java8"
            abi = subprocess.check_output([*adb,"shell","getprop","ro.product.cpu.abi"]).decode().strip()
            arch = home + "/lib/" + {"x86":"i386","x86_64":"amd64","arm64-v8a":"aarch64","armeabi-v7a":"arm"}[abi]
            vmdir = subprocess.check_output([*adb,"shell",f"ls {arch}/*/libjvm.so"]).decode().strip().splitlines()[0].rsplit("/",1)[0]
            subprocess.run([*adb,"shell","mkdir",remote],check=True)
            for f,name in [(args.bridge,"bridge.jar"),(args.game,"game.jar"),(args.native/"liblumenclassic.so","liblumenclassic.so")]:
                subprocess.run([*adb,"push",str(f),remote+"/"+name],check=True)
            subprocess.run([*adb,"reverse",f"tcp:{port}",f"tcp:{port}"],check=True)
            command = [*adb,"shell",f"cd {remote} && LD_LIBRARY_PATH={vmdir}:{arch}:{arch}/jli:{home}/lib:{remote} "
                       f"JAVA_HOME={home} TMPDIR={remote} HOME={remote} {runtime}/liblumenjvm.so "
                       f"{vmdir}/libjvm.so {home} 8 256 {remote}/bridge.jar:{remote}/game.jar "
                       f"dev.lumencraft.bridge.Session {port} {token} {remote}"]
        def saved_level():
            output = Path(work)/"level.dat"
            if args.adb:
                subprocess.run([*adb,"pull",remote+"/level.dat",str(output)],check=True)
            return gzip.decompress(output.read_bytes())
        with (args.results / "game.log").open("wb") as log:
            child = subprocess.Popen(command,cwd=work,stdout=log,stderr=subprocess.STDOUT)
            try:
                with listener.accept()[0] as conn, conn.makefile("rb") as stream:
                    conn.settimeout(150 if args.adb else 40)
                    length, = struct.unpack(">H",stream.read(2))
                    assert stream.read(length).decode() == token
                    w,h = struct.unpack(">ii",stream.read(8))
                    assert (w,h) == (320,240)
                    samples = {}
                    first = None
                    saved_before = None
                    for i in range(420):
                        length, = struct.unpack(">i",stream.read(4))
                        assert length == w*h*4
                        frame = stream.read(length)
                        assert len(frame) == length
                        if i == 3:
                            conn.sendall(struct.pack(">iii",2,0,-170))
                        if first is None and i >= 100 and i % 10 == 0:
                            colors = len(set(zip(frame[::4],frame[1::4],frame[2::4])))
                            if colors > 8:
                                first = i
                            elif i % 40 == 0:
                                conn.sendall(struct.pack(">iii",2,600,-40))
                        if first is None:
                            continue
                        age = i - first
                        if age in (0, 20):
                            colors = len(set(zip(frame[::4],frame[1::4],frame[2::4])))
                            samples[age] = {"distinct_rgb":colors,"sha256":hashlib.sha256(frame).hexdigest()}
                            png(args.results / f"frame-{age}.png",w,h,frame)
                        if age == 1:
                            conn.sendall(struct.pack(">iii",1,17,1))  # LWJGL W
                            conn.sendall(struct.pack(">iii",2,80,-350))
                        if age == 5:
                            conn.sendall(struct.pack(">iii",1,17,0))
                        if age == 22:
                            conn.sendall(struct.pack(">iii",1,28,1))  # Enter saves the level.
                            conn.sendall(struct.pack(">iii",1,28,0))
                        if age == 30:
                            saved_before = saved_level()
                            conn.sendall(struct.pack(">iii",3,1,1))  # Original build uses RMB for removal.
                            conn.sendall(struct.pack(">iii",3,1,0))
                        if age == 55:
                            break
                    assert saved_before is not None, "No rendered terrain"
                    assert samples[0]["sha256"] != samples[20]["sha256"]
                    conn.sendall(struct.pack(">iii",4,0,0))
                    while stream.read(65536):
                        pass
                assert child.wait(20) == 0
                saved_after = saved_level()
                changed = sum(a != b for a,b in zip(saved_before,saved_after))
                assert len(saved_before) == len(saved_after) and changed == 1, "Block removal did not persist"
                result = {"frames":i+1,"size":[w,h],"samples":samples,"clean_exit":True,"changed_block_bytes":changed,
                          "save_files":[f.name for f in Path(work).iterdir() if f.is_file()]}
                (args.results / "session.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
                print(json.dumps(result))
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()
                if args.adb:
                    subprocess.run([*adb,"reverse","--remove",f"tcp:{port}"],check=False)
if __name__ == "__main__":
    main()
