#!/usr/bin/env python3
"""Build the release-bound agent graph ZIP and its SHA-256 sidecar (stdlib only)."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
validate = runpy.run_path(str(Path(__file__).with_name("validate-agent-docs.py")))["validate"]


def build(graph: Path, version: str, source_commit: str, output: Path) -> None:
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]*", version):
        raise ValueError("component version must be a resolved Maven version")
    if not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", source_commit):
        raise ValueError("source commit must be a full Git commit hash")
    validate(graph)
    manifest = (graph / "manifest.json").read_bytes()
    inventory = json.loads(manifest)
    files = {"manifest.json": manifest}
    for article in inventory["articles"]:
        files[article["source"]] = (graph / article["source"]).read_bytes()
    # Frame the sorted paths and file digests so renames and content changes both affect identity.
    content_hash = hashlib.sha256()
    for name, data in sorted(files.items()):
        content_hash.update(f"{name}\0{hashlib.sha256(data).hexdigest()}\n".encode("utf-8"))
    release = {
        "schemaVersion": 1,
        "namespace": inventory["namespace"],
        "componentVersion": version,
        "sourceCommit": source_commit,
        "contentHash": content_hash.hexdigest(),
    }
    files["release.json"] = (json.dumps(release, indent=2) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    output.with_suffix(output.suffix + ".sha256").write_text(
        hashlib.sha256(output.read_bytes()).hexdigest() + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-commit", default="HEAD")
    parser.add_argument("--graph", type=Path, default=ROOT / "docs" / "agents")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source_commit = args.source_commit
    if source_commit == "HEAD":
        source_commit = subprocess.check_output(
            ["git", "-C", str(ROOT), "rev-parse", "--verify", "HEAD"], text=True).strip()
    build(args.graph, args.version, source_commit, args.output)
    print(f"Built {args.output.name} for {args.version} ({args.output.stat().st_size} bytes).")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError, AttributeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Cannot package agent documentation: {error}")
