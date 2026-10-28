#!/usr/bin/env python3
"""Check a source tree or .zip for files and text that must never be published.

Usage:
  python3 scripts/check-public-tree.py [PATH ...] [--markers FILE] [--allow-git]

PATH is a directory or a .zip archive (default: the repository root). --markers adds literal
private strings, one per line ("#" starts a comment); matching ignores case. A line starting
with "author:" is a name that may appear only in LICENSE; a "site:" prefix is ignored. On a hit the script prints only the
file path and the rule name, never the matching text. --allow-git skips a top-level .git
directory (for checking a clone). Exit status is 1 when anything is found.
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent

FORBIDDEN_DIRS = {
    ".git", "node_modules", ".gradle", ".kotlin", ".toolchain", ".wrangler", ".vinext", ".next",
    ".sites-runtime", "build", "dist", "outputs", "downloads", ".idea", "__pycache__", "coverage",
}
FORBIDDEN_SUFFIXES = {
    ".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".der", ".sqlite",
    ".sqlite3", ".db", ".db-wal", ".db-shm", ".zip", ".tar", ".gz", ".tgz", ".log", ".tsbuildinfo",
    ".dex", ".class",
}
FORBIDDEN_NAMES = {"local.properties", ".npmrc.local", "google-services.json", "keystore.properties"}
FORBIDDEN_PREFIXES = (".env", ".dev.vars", "health-connect-export-", "RELEASE-", "VERIFICATION")
# Binary files that belong in the source tree.
ALLOWED_BINARIES = {"android/gradle/wrapper/gradle-wrapper.jar"}
# The only file that may contain "author:" markers.
AUTHOR_FILES = {"LICENSE"}

TEXT_RULES = [
    ("private key", re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("device token", re.compile(rb"hcd_[A-Za-z0-9_-]{43}")),
    ("pairing token", re.compile(rb"hcp_[A-Za-z0-9_-]{43}")),
    ("connection code", re.compile(rb"HCS1\.[A-Za-z0-9_-]{40,}")),
    ("Sites project, app, or deployment ID", re.compile(rb"appg[a-z]{2,4}_[0-9a-f]{16,}")),
    ("plugin or connector ID", re.compile(rb"(plugin" rb"_asdk_|plugin" rb"_connector_|connector_[0-9a-f]{20,})")),
    ("API key", re.compile(rb"(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,})")),
    ("bearer value", re.compile(rb"Bearer [A-Za-z0-9._~+/-]{24,}=*")),
    ("absolute home path", re.compile(rb"(/home/[a-z_][a-z0-9_-]*/|/Users/[A-Za-z][^/\s]*/|[A-Za-z]:\\\\Users\\\\)")),
]
EMAIL_RE = re.compile(rb"[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+\.)+[A-Za-z]{2,}")
ALLOWED_EMAIL_DOMAINS = (b"example.com", b"example.org", b"example.net", b"example", b"test", b"invalid")
SITE_HOST_RE = re.compile(rb"[a-z0-9-]+(\.[a-z0-9-]+)*\.chatgpt\.site", re.IGNORECASE)
ALLOWED_SITE_HOSTS = {
    b"example.chatgpt.site", b"your-site.chatgpt.site", b"alex-health.alex.chatgpt.site",
    b"health-copy.someone.chatgpt.site",
}


def load_markers(path: Path | None) -> list[tuple[bytes, bool]]:
    """Returns (marker, allowed in LICENSE) pairs."""
    if path is None:
        return []
    markers = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            author = line.startswith("author:")
            value = line.removeprefix("author:").removeprefix("site:").strip()
            if value:
                markers.append((value.lower().encode("utf-8"), author))
    return markers


def iter_dir(root: Path, allow_git: bool = False):
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if allow_git and (rel == ".git" or rel.startswith(".git/")):
            continue
        if path.is_symlink():
            yield rel, None, "symlink"
        elif path.is_file():
            yield rel, path.read_bytes(), "file"
        elif path.is_dir():
            yield rel, None, "dir"


def iter_zip(archive: Path):
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        # Archives have one top-level folder; check paths below it.
        prefix = names[0].split("/", 1)[0] + "/" if names else ""
        for info in z.infolist():
            rel = info.filename[len(prefix):] if info.filename.startswith(prefix) else info.filename
            if not rel:
                continue
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                yield rel.rstrip("/"), None, "symlink"
            elif info.is_dir():
                yield rel.rstrip("/"), None, "dir"
            else:
                yield rel, z.read(info), "file"


def check(entries, markers: list[tuple[bytes, bool]]) -> list[tuple[str, str]]:
    hits: list[tuple[str, str]] = []
    for rel, data, kind in entries:
        parts = PurePosixPath(rel).parts
        name = parts[-1]
        if kind == "symlink":
            hits.append((rel, "symlink"))
            continue
        bad_dir = next((p for p in (parts if kind == "dir" else parts[:-1]) if p in FORBIDDEN_DIRS), None)
        if bad_dir:
            if kind == "dir" and name == bad_dir:
                hits.append((rel, "forbidden directory"))
            continue
        if kind == "dir":
            continue
        suffix = PurePosixPath(name).suffix.lower()
        if rel not in ALLOWED_BINARIES and (
            suffix in FORBIDDEN_SUFFIXES or name in FORBIDDEN_NAMES or name.startswith(FORBIDDEN_PREFIXES) or suffix == ".jar"
        ):
            hits.append((rel, "forbidden file type"))
            continue
        assert data is not None
        for rule, pattern in TEXT_RULES:
            if pattern.search(data):
                hits.append((rel, rule))
        for match in EMAIL_RE.finditer(data):
            domain = match.group(0).split(b"@", 1)[1].lower()
            if not any(domain == d or domain.endswith(b"." + d) for d in ALLOWED_EMAIL_DOMAINS):
                hits.append((rel, "email address"))
                break
        for match in SITE_HOST_RE.finditer(data):
            if match.group(0).lower() not in ALLOWED_SITE_HOSTS:
                hits.append((rel, "Site hostname"))
                break
        lowered = data.lower()
        for index, (marker, author) in enumerate(markers, 1):
            if marker in lowered and not (author and rel in AUTHOR_FILES):
                hits.append((rel, f"private marker #{index}"))
    return hits


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("paths", nargs="*", type=Path, default=[ROOT])
    parser.add_argument("--markers", type=Path)
    parser.add_argument("--allow-git", action="store_true")
    args = parser.parse_args(argv)
    markers = load_markers(args.markers)
    failed = False
    for target in args.paths:
        entries = iter_zip(target) if target.is_file() and target.suffix == ".zip" else iter_dir(target, args.allow_git)
        hits = check(entries, markers)
        label = target.resolve().name
        for rel, rule in hits:
            print(f"{label}: {rel}: {rule}")
        failed |= bool(hits)
        print(f"{label}: {'FAILED' if hits else 'clean'} ({len(hits)} finding(s))")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
