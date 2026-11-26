"""Reproduce libc++ companions for the published Java 25 Android runtimes."""
import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile


ARCHIVE = "https://dl.google.com/android/repository/android-ndk-r28c-windows.zip"
NOTICE = "faf0dc52cf6ce9ab09a04b0c074a378dd2565ee1ed9bc1179d83793bcdba012e"
PINS = [
    ("arm64-v8a", "aarch64-linux-android", "ab4e6c71b96b851de45a8a9bd86369e7dbc2130a44b3b4520564be94847910f2",
     "cd61762848882a16c8244c964a6f396c0caa0b440588a210ce9cc4ab0e6d9f0c"),
    ("armeabi-v7a", "arm-linux-androideabi", "10ee48131d6742899619f7cfb3b8df150ad787d723c766a3fb80d4bb275711ff",
     "e7e20f6e0f3f181e853cea50373ecee2ccfe95c668dce72d0999b2469c92d0e1"),
    ("x86_64", "x86_64-linux-android", "e4cd73c8a3607269f3be58d15c21f78bff112e27f9398d6261e5f965668f8746",
     "2e855857f80819737d25a663109fedf816a5e996a85423166806014a06593256"),
]


class RemoteZip(io.RawIOBase):
    """Seek across one Google distribution via HTTPS Range requests."""
    def __init__(self):
        super().__init__()
        request = urllib.request.Request(ARCHIVE, method="HEAD")
        with urllib.request.urlopen(request, timeout=45) as reply:
            self.length = int(reply.headers["content-length"])
        self.pos = 0

    def seek(self, offset, whence=0):
        origin = (0, self.pos, self.length)[whence]
        self.pos = origin + offset
        return self.pos

    def tell(self):
        return self.pos

    def read(self, size=-1):
        count = self.length - self.pos if size < 0 else min(size, self.length - self.pos)
        if count <= 0:
            return b""
        end = self.pos + count - 1
        req = urllib.request.Request(ARCHIVE, headers={"Range": f"bytes={self.pos}-{end}"})
        with urllib.request.urlopen(req, timeout=45) as reply:
            if reply.status != 206 or reply.headers["Content-Range"] != f"bytes {self.pos}-{end}/{self.length}":
                raise OSError("Invalid Google NDK range response")
            data = reply.read(count)
        if len(data) != count:
            raise EOFError("Incomplete NDK archive")
        self.pos += count
        return data


def checked(data, expected):
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("NDK component failed its pinned SHA-256")
    return data


def vendor(ndk24, get):
    main = Path(__file__).resolve().parents[2] / "app/src/main"
    strip = ndk24 / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
    for abi, triple, source_hash, final_hash in PINS:
        path = "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/" + triple + "/libc++_shared.so"
        dest = main / "jniLibs" / abi / "libc++_shared.so"
        with tempfile.TemporaryDirectory(dir=dest.parent) as tmp:
            stage = Path(tmp) / dest.name
            stage.write_bytes(checked(get(path), source_hash))
            subprocess.run([str(strip), "--strip-unneeded", str(stage)], check=True)
            checked(stage.read_bytes(), final_hash)
            stage.replace(dest)
    notice = main / "assets/licenses/Android-NDK-r28c-NOTICE.txt"
    notice.parent.mkdir(exist_ok=True, parents=True)
    notice.write_bytes(checked(get("NOTICE"), NOTICE))


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--ndk24", required=True, type=Path, help="official NDK r24, including llvm-strip.exe")
    cli.add_argument("--ndk28", type=Path, help="optional local Google NDK r28c Windows directory")
    args = cli.parse_args()
    if args.ndk28:
        vendor(args.ndk24, lambda name: (args.ndk28 / name).read_bytes())
    else:
        with zipfile.ZipFile(RemoteZip()) as archive:
            vendor(args.ndk24, lambda name: archive.read("android-ndk-r28c/" + name))
