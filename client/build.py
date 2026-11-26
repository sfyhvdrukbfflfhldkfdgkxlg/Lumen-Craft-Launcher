"""Build the Java 8 client bridge and the exact OSMesa subset linked on Android."""
import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import platform
import re
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

BASE = Path(__file__).resolve().parent
APP = BASE.parent / "app/src/main"
ARCHIVE = "https://archive.mesa3d.org/older-versions/3.x/MesaLib-3.4.2.tar.gz"
CHECKSUM = "b02b5f77321175820b9955b07979d9f8c5d52e146eecc719844380ef2849ddd6"
TARGETS = {"x86": "i686-linux-android23", "x86_64": "x86_64-linux-android23",
           "arm64-v8a": "aarch64-linux-android23", "armeabi-v7a": "armv7a-linux-androideabi23"}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--windows-test-cc", type=Path, help="TinyCC x64 compiler for the Windows host regression")
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME"), help="JDK with include/win32 for the host test")
    parser.add_argument("--work", type=Path, default=Path(tempfile.gettempdir()) / "lumen-osmesa")
    parser.add_argument("--abis", nargs="+", choices=TARGETS, default=list(TARGETS))
    args = parser.parse_args()
    if not args.windows_test_cc and not args.ndk:
        parser.error("Supply --ndk or ANDROID_NDK_HOME for an Android build")
    args.work.mkdir(parents=True, exist_ok=True)
    archive = args.work / "mesa.tar.gz"
    vendored = BASE / "upstream" / "MesaLib-3.4.2.tar.gz"
    if vendored.is_file():
        archive = vendored
    if not archive.is_file():
        urllib.request.urlretrieve(ARCHIVE, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != CHECKSUM:
        raise SystemExit("Mesa archive digest mismatch")
    mesa = args.work / "Mesa-3.4.2"
    stamp = args.work / (CHECKSUM + ".ready")
    if not stamp.exists() or not mesa.is_dir():
        with tarfile.open(archive) as bundle:
            bundle.extractall(args.work, filter="data")
        stamp.touch()
    # Bionic uses the portable floating-point path. The upstream x87 control header is glibc-specific.
    for header in ["glheader.h", "mmath.h"]:
        path = mesa / "src" / header
        source = path.read_bytes()
        old = b"#if defined(__linux__) && defined(__i386__)"
        if old + b" && !defined(__ANDROID__)" not in source:
            path.write_bytes(source.replace(old, old + b" && !defined(__ANDROID__)"))
    config = mesa / "src/config.c"
    source = config.read_bytes()
    config.write_bytes(source.replace(b"printf( n->data.l.head->data.w.text );", b'printf("%s", n->data.l.head->data.w.text);'))
    template = mesa / "src/texutil_tmp.h"
    source = template.read_bytes()
    template.write_bytes(re.sub(rb'__FUNCTION__ "(:?)\\n"', rb'"%s\1\\n", __func__', source))
    texutil = mesa / "src/texutil.c"
    source = texutil.read_bytes()
    texutil.write_bytes(re.sub(rb'#define ERROR_STRING\s+(__FUNCTION__|__FILE__ ":" __LINE__)',
                               b'#define ERROR_STRING "texture conversion"', source))
    host = {"Windows":"windows-x86_64","Linux":"linux-x86_64","Darwin":"darwin-x86_64"}[platform.system()]
    makefile = (mesa / "src/Makefile.X11").read_text()
    names = re.findall(r"([a-zA-Z0-9_/]+\.c)\b", makefile.split("CORE_SOURCES =",1)[1].split("DRIVER_SOURCES =",1)[0])
    sources = [mesa / "src" / n for n in names] + [mesa / "src/OSmesa/osmesa.c", BASE / "native/bridge.c"]
    assets = APP / "assets"
    assets.mkdir(exist_ok=True, parents=True)
    if args.windows_test_cc:
        if not args.jdk:
            parser.error("--jdk or JAVA_HOME must identify a Windows JDK")
        # Windows LLP64 unsigned long cannot hold a native pointer. Android uses ILP32 or LP64.
        mem = mesa / "src/mem.c"
        source = mem.read_bytes()
        mem.write_bytes(source.replace(b"unsigned long ptr, buf", b"size_t ptr, buf")
                        .replace(b"(unsigned long)", b"(size_t)").replace(b"(unsigned long *)", b"(size_t *)"))
        subprocess.run([str(args.windows_test_cc), "-shared", "-include", "windows.h",
                        "-I"+str(mesa/"src"), "-I"+str(mesa/"include"), "-I"+str(args.jdk/"include"),
                        "-I"+str(args.jdk/"include/win32"), "-o", str(args.work/"liblumenclassic.so"),
                        *map(str,sources)],check=True)
    for abi in [] if args.windows_test_cc else args.abis:
        clang = Path(args.ndk) / "toolchains/llvm/prebuilt" / host / "bin" / ("clang.exe" if os.name == "nt" else "clang")
        output = args.work / abi
        output.mkdir(exist_ok=True)
        flags = [str(clang), "--target=" + TARGETS[abi], "-O2", "-fPIC", "-fvisibility=hidden",
                 "-ffunction-sections", "-fdata-sections", "-fno-strict-aliasing", "-fwrapv", "-std=gnu99",
                 "-Wno-implicit-function-declaration",
                 "-I" + str(mesa / "src"), "-I" + str(mesa / "include")]
        def compile_file(source):
            obj = output / (source.parent.name + "-" + source.stem + ".o")
            if obj.exists() and obj.stat().st_mtime >= max(source.stat().st_mtime, clang.stat().st_mtime):
                return obj
            subprocess.run([*flags, "-c", str(source), "-o", str(obj)], check=True)
            return obj
        with concurrent.futures.ThreadPoolExecutor(min(6, os.cpu_count() or 1)) as pool:
            objects = list(pool.map(compile_file, sources))
        lib = APP / "jniLibs" / abi / "liblumenclassic.so"
        lib.parent.mkdir(exist_ok=True, parents=True)
        subprocess.run([*flags, "-shared", "-fuse-ld=lld", "-Wl,--gc-sections,-z,defs,-z,max-page-size=16384,-z,relro,-z,now",
                        "-Wl,-soname,liblumenclassic.so", "-s", *map(str,objects), "-lm", "-o",str(lib)],check=True)
        print(abi, lib.stat().st_size, flush=True)
    classes = args.work / "bridge-classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((BASE / "java").rglob("*.java"))
    subprocess.run(["javac","--release","8","-Xlint:-options","-encoding","UTF-8","-d",str(classes),*map(str,sources)],check=True)
    with zipfile.ZipFile(assets / "classic-bridge.jar", "w", zipfile.ZIP_DEFLATED) as jar:
        for file in sorted(classes.rglob("*.class")):
            jar.write(file, file.relative_to(classes).as_posix())

if __name__ == "__main__":
    main()
