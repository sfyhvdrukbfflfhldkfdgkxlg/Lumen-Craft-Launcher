"""Publish only verified assets. Existing tags and published releases are immutable."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from package import ABIS, PACKAGE, apk_name, require, sha256, version


def run(*args):
    return subprocess.check_output(list(map(str, args)), text=True, encoding="utf-8").strip()


def git(*args):
    return run("git", *args)


def main():
    tag, code = version()
    commit = git("rev-parse", "HEAD")
    require(commit == os.environ["GITHUB_SHA"] or commit == git("rev-parse", os.environ["GITHUB_SHA"] + "^{}"),
            "Checkout does not match the workflow revision")
    require(os.environ["GITHUB_REF"] in ("refs/heads/main", "refs/tags/" + tag), "Unexpected publication ref")
    expected = sorted([apk_name(abi) for abi in ABIS | {"universal"}] + ["SHA256SUMS.txt"])
    files = Path("verified-release")
    require(sorted(p.name for p in files.iterdir()) == expected, "Unexpected release artifacts")
    sums = (files / "SHA256SUMS.txt").read_text(encoding="ascii").splitlines()
    require(sorted(sums) == sorted(f"{sha256(files / name)}  {name}" for name in expected if name.endswith(".apk")),
            "Release assets changed after verification")
    tags = git("tag", "--list").splitlines()
    require(code > 2, "Release versionCode must permit upgrades from Alpha1.0.0")
    for older in tags:
        if older == tag or not re.fullmatch(r"(?:Alpha|Beta|RC|v)?\d+\.\d+\.\d+", older):
            continue
        if "release.properties" in git("ls-tree", "--name-only", older, "release.properties").splitlines():
            previous = re.search(r"^versionCode=(\d+)$", git("show", older + ":release.properties"), re.M)
            require(previous and int(previous[1]) < code, f"versionCode is not newer than {older}")
    if tag in tags:
        require(git("rev-parse", tag + "^{}") == commit, "Tag already belongs to another revision")
    else:
        git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
        git("config", "user.name", "github-actions[bot]")
        git("tag", "-a", tag, commit, "-m", f"LumenCraft {tag}")
        git("push", "origin", "refs/tags/" + tag)
    pre = tag.startswith(("Alpha", "Beta", "RC"))
    repo = os.environ["GITHUB_REPOSITORY"]
    releases = run("gh", "api", "--paginate", f"repos/{repo}/releases?per_page=100", "--jq",
                   f'.[] | select(.tag_name == "{tag}") | .id').splitlines()
    require(len(releases) <= 1, "Ambiguous GitHub release")
    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        if not releases:
            parents = git("tag", "--merged", "HEAD", "--sort=-creatordate").splitlines()
            prior = next((t for t in parents if t != tag and re.fullmatch(r"(?:Alpha|Beta|RC|v)?\d+\.\d+\.\d+", t)), None)
            history = git("log", "--no-merges", "--format=- %s (%h)", f"{prior}..HEAD" if prior else "HEAD", "--", ".", ":(exclude)dist")
            notes = work / "notes.md"
            authored = Path("docs/releases") / (tag + ".md")
            notes.write_text((authored.read_text(encoding="utf-8") + "\n\n" if authored.exists() else "") +
                             f"## Commits\n\n{history}\n\nUniversal and per-ABI APKs for {PACKAGE}. See SHA256SUMS.txt.\n", encoding="utf-8")
            run("gh", "release", "create", tag, "--verify-tag", "--draft", "--title", f"LumenCraft {tag}",
                "--notes-file", notes, *(["--prerelease"] if pre else []))
        info = json.loads(run("gh", "release", "view", tag, "--json", "isDraft,isPrerelease,assets"))
        require(info["isPrerelease"] == pre, "Release channel differs")
        remote = {a["name"] for a in info["assets"]}
        require(remote <= set(expected), "Release contains unexpected assets")
        missing = sorted(set(expected) - remote)
        if missing:
            require(info["isDraft"], "Existing published release is incomplete; refusing to modify it")
            run("gh", "release", "upload", tag, *(files / f for f in missing))
        downloaded = work / "downloaded"
        run("gh", "release", "download", tag, "--dir", downloaded)
        for name in expected:
            require(sha256(files / name) == sha256(downloaded / name), f"GitHub digest differs for {name}")
        if info["isDraft"]:
            run("gh", "release", "edit", tag, "--draft=false")
        print(run("gh", "release", "view", tag, "--json", "url", "--jq", ".url"))


if __name__ == "__main__":
    main()
