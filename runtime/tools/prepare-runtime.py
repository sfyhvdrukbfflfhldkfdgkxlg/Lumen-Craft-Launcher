"""Convert an upstream Android JRE .tar.xz to a ZIP for offline SAF import.

Example: python prepare-runtime.py jre8-android-x86.tar.xz java8-x86.zip
Official pinned archives are in app/src/main/assets/openjdk.json.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import shutil
import tarfile
import zipfile


def convert(source: Path, dest: Path):
    pins = json.loads((Path(__file__).resolve().parents[2] / "app/src/main/assets/openjdk.json").read_text())
    entry = next((e for e in pins.values() if e["url"].rsplit("/", 1)[-1] == source.name), None)
    if entry is None:
        raise ValueError("Expected a named archive from the pinned Android OpenJDK catalog")
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != entry["sha256"] or source.stat().st_size != entry["size"]:
        raise ValueError("Archive integrity check failed")
    with tarfile.open(source, "r:xz") as tar:
        members = tar.getmembers()
        files = [item for item in members if item.isfile()]
        if not any(item.name.endswith("/libjvm.so") for item in files):
            raise ValueError("Missing HotSpot")
        links = {}
        by_name = {posixpath.normpath(item.name): item for item in members}
        for item in members:
            name = PurePosixPath(item.name)
            if name.is_absolute() or ".." in name.parts:
                raise ValueError("Unexpected path in pinned archive")
            if not (item.islnk() or item.issym()):
                continue
            target = item.linkname
            if item.issym():
                target = posixpath.join(posixpath.dirname(item.name), target)
            target = posixpath.normpath(target)
            if target not in by_name:
                raise ValueError("Unresolved archive link")
            if by_name[target].isdir():
                continue
            if not by_name[target].isfile():
                raise ValueError("Unsupported link chain")
            links.setdefault(target, []).append(name.as_posix())
        # A new ZIP gets created exclusively; a failed conversion removes only that new file.
        with dest.open("xb") as raw:
            try:
                with zipfile.ZipFile(raw, "w", zipfile.ZIP_DEFLATED) as packed:
                    for item in files:
                        name = PurePosixPath(item.name).as_posix()
                        for output_name in [name, *links.get(name, [])]:
                            with tar.extractfile(item) as inp, packed.open(output_name, "w") as out:
                                shutil.copyfileobj(inp, out)
            except BaseException:
                raw.close()
                dest.unlink()
                raise


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    cli.add_argument("output", type=Path)
    args = cli.parse_args()
    convert(args.source, args.output)
