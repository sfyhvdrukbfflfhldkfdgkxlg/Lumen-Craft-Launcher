"""Sign and verify independently built LumenCraft release APKs. Requires Python 3.11+."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
SIGNER = "517e9aaddda76fe7abfc9dd9b64b619de8cbbf5f37235f2036d0210fd56cb0a8"
V1_SIGNATURE = re.compile(r"META-INF/(?:MANIFEST\.MF|[\w-]+\.(?:SF|RSA|DSA|EC))", re.ASCII)
PACKAGE = "dev.lumencraft.android"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version():
    props = dict(line.split("=", 1) for line in (ROOT / "release.properties").read_text().splitlines() if line)
    name = props["versionName"]
    require(re.fullmatch(r"(?:Alpha|Beta|RC|v)?\d+\.\d+\.\d+", name), "Invalid release name")
    return name, int(props["versionCode"])


def produced_apks():
    folder = ROOT / "app/build/outputs/apk/release"
    data = json.loads((folder / "output-metadata.json").read_text(encoding="utf-8"))
    name, code = version()
    require(data["applicationId"] == PACKAGE and data["variantName"] == "release", "Expected app release output")
    outputs = {}
    for entry in data["elements"]:
        filters = entry["filters"]
        require(not filters or (len(filters) == 1 and filters[0]["filterType"] == "ABI"), "Unexpected APK filter")
        abi = filters[0]["value"] if filters else "universal"
        require(abi not in outputs and (entry["versionName"], entry["versionCode"]) == (name, code), "Version or ABI mismatch")
        outputs[abi] = folder / entry["outputFile"]
    require(set(outputs) == ABIS | {"universal"}, "Build with -PreleaseSplits=true")
    return outputs


def run(*cmd, env=None):
    return subprocess.run([str(v) for v in cmd], capture_output=True, text=True, errors="replace", check=True, env=env).stdout


def java_tool():
    jdk = os.environ.get("JAVA_HOME")
    return str(Path(jdk) / "bin/java") if jdk else "java"


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "ANDROID_HOME is required")
    return Path(sdk) / "build-tools/35.0.0"


def apksigner(*args, env=None):
    return run(java_tool(), "-jar", build_tools() / "lib/apksigner.jar", *args, env=env)


def sha256(path):
    with path.open("rb") as src:
        return hashlib.file_digest(src, "sha256").hexdigest()


def payload(apk):
    entries = {}
    names = set()
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            require(info.filename not in names, f"Duplicate entry: {info.filename}")
            names.add(info.filename)
            if V1_SIGNATURE.fullmatch(info.filename):
                continue
            with archive.open(info) as stream:
                entries[info.filename] = (info.compress_type, hashlib.file_digest(stream, "sha256").hexdigest())
    return entries


def apk_name(abi):
    return f"LumenCraft-{version()[0]}-{abi}.apk"


def verify(folder):
    name, code = version()
    sums = []
    suffix = ".exe" if os.name == "nt" else ""
    for abi, built in sorted(produced_apks().items()):
        apk = folder / apk_name(abi)
        require(apk.is_file(), f"Missing {apk}")
        signed = payload(apk)
        rebuilt = payload(built)
        different = sorted(key for key in set(signed) | set(rebuilt) if signed.get(key) != rebuilt.get(key))
        require(not different, f"{apk.name} differs from this build at {different[:6]}")
        sig = apksigner("verify", "--verbose", "--print-certs", apk)
        certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: (\w+)", sig)
        require(certs == [SIGNER], f"Upgrade certificate mismatch for {apk.name}")
        for scheme in ("v1", "v2"):
            require(re.search(rf"Verified using {scheme} scheme [^\n]*: true", sig), f"{apk.name} lacks {scheme}")
        run(build_tools() / ("zipalign" + suffix), "-c", "4", apk)
        manifest = run(build_tools() / ("aapt" + suffix), "dump", "badging", apk)
        require(f"package: name='{PACKAGE}' versionCode='{code}' versionName='{name}'" in manifest, "Wrong application or version")
        require("application-debuggable" not in manifest and "sdkVersion:'23'" in manifest, "Invalid release flags or minSdk")
        supported = ABIS if abi == "universal" else {abi}
        native = {key.split("/")[1] for key in signed if key.startswith("lib/")}
        require(native == supported, f"Incorrect APK architectures for {apk.name}: {native}")
        for arch in supported:
            for lib in ("libterracotta", "libroomnet23", "liblumenjvm", "liblumenclassic", "liblumengl", "liblumencap"):
                require(f"lib/{arch}/{lib}.so" in signed, f"Missing {arch}/{lib}.so")
        sums.append(f"{sha256(apk)}  {apk.name}")
    return "\n".join(sums) + "\n"


def check_candidate(folder):
    expected = verify(folder)
    require((folder / "SHA256SUMS.txt").read_bytes() == expected.encode("ascii"), "SHA256SUMS.txt bytes differ from the release manifest")
    allowed = {"SHA256SUMS.txt", *(apk_name(abi) for abi in ABIS | {"universal"})}
    require({p.name for p in folder.iterdir()} == allowed, "Unexpected file in the APK release directory")
    print(expected, end="")


def sign(folder, installed):
    env = os.environ.copy()
    with tempfile.TemporaryDirectory(prefix="lumencraft-signer-") as temp:
        work = Path(temp)
        if installed:
            keystore = Path.home() / ".android/debug.keystore"
            alias = "androiddebugkey"
            # Android Studio's standard legacy alpha keystore. Its certificate is pinned above.
            env.setdefault("LUMEN_STORE_PASSWORD", "android")
        else:
            alias = env.get("LUMEN_KEY_ALIAS")
            if env.get("LUMEN_KEYSTORE_PATH"):
                keystore = Path(env["LUMEN_KEYSTORE_PATH"])
            else:
                require(env.get("LUMEN_KEYSTORE_BASE64"), "Missing signing keystore. Supply release secrets or a signed candidate.")
                keystore = work / "signing.keystore"
                keystore.write_bytes(base64.b64decode(env["LUMEN_KEYSTORE_BASE64"], validate=True))
        require(alias and keystore.is_file() and env.get("LUMEN_STORE_PASSWORD"), "Missing signing credentials")
        env["LUMEN_KEY_PASSWORD"] = env.get("LUMEN_KEY_PASSWORD") or env["LUMEN_STORE_PASSWORD"]
        staged = work / "apks"
        staged.mkdir()
        for abi, built in produced_apks().items():
            apksigner("sign", "--ks", keystore, "--ks-key-alias", alias, "--ks-pass", "env:LUMEN_STORE_PASSWORD",
                      "--key-pass", "env:LUMEN_KEY_PASSWORD", "--v1-signing-enabled", "true",
                      "--v2-signing-enabled", "true", "--v4-signing-enabled", "false",
                      "--out", staged / apk_name(abi), built, env=env)
        (staged / "SHA256SUMS.txt").write_text(verify(staged), encoding="ascii", newline="\n")
        if folder.exists():
            check_candidate(folder)
            require((folder / "SHA256SUMS.txt").read_bytes() == (staged / "SHA256SUMS.txt").read_bytes(),
                    "A different signed candidate already exists. Choose --output to stage a new local candidate.")
        else:
            folder.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(staged, folder)
        print(f"Verified five signed APKs in {folder}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["sign", "verify", "version"])
    parser.add_argument("--installed-alpha-key", action="store_true")
    parser.add_argument("--output", type=Path, help="Candidate directory (defaults to dist/<version>)")
    args = parser.parse_args()
    folder = args.output or ROOT / "dist" / version()[0]
    if args.command == "version":
        print(version()[0])
    elif args.command == "verify":
        check_candidate(folder)
    else:
        sign(folder, args.installed_alpha_key)


if __name__ == "__main__":
    main()
