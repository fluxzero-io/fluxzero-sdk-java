#!/usr/bin/env python3
"""Reconstruct reviewed historical graphs and build local SDK documentation classifiers."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import runpy
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HISTORY = ROOT / "docs/agents-history"
package = runpy.run_path(str(Path(__file__).with_name("build-agent-docs.py")))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    if (path.is_absolute() or ".." in path.parts or "\\" in name
            or str(path) != name or not path.parts):
        raise ValueError(f"unsafe graph path: {name}")
    if name not in ("manifest.json", "README.md") and not (
            name.startswith("articles/") and name.endswith(".md")):
        raise ValueError(f"unexpected graph file: {name}")
    return name


def content_hash(files):
    manifest = json.loads(files["manifest.json"])
    names = ["manifest.json"] + [safe_path(a["source"]) for a in manifest["articles"]]
    digest = hashlib.sha256()
    for name in sorted(names):
        digest.update(f"{name}\0{sha(files[name])}\n".encode())
    return digest.hexdigest()


def apply_patch(files, patch):
    """Apply generated unified diffs with exact offsets and context, without fuzz or shell execution."""
    result = dict(files)
    lines = patch.decode("utf-8").splitlines(keepends=True)
    i = 0
    changed = set()
    while i < len(lines):
        if not lines[i].startswith("--- ") or i + 1 >= len(lines) or not lines[i + 1].startswith("+++ "):
            raise ValueError("invalid patch file header")
        old, new = lines[i][4:].rstrip("\n"), lines[i + 1][4:].rstrip("\n")
        i += 2
        if old != "/dev/null" and not old.startswith("a/"):
            raise ValueError("invalid old path prefix")
        if new != "/dev/null" and not new.startswith("b/"):
            raise ValueError("invalid new path prefix")
        name = safe_path(new[2:] if new != "/dev/null" else old[2:])
        if old != "/dev/null" and old[2:] != name:
            raise ValueError("patch renames are unsupported")
        if name in changed or (old == "/dev/null") == (name in result):
            raise ValueError(f"duplicate or inconsistent patch target: {name}")
        changed.add(name)
        source = result.get(name, b"").decode("utf-8").splitlines(keepends=True)
        output, position = [], 0
        hunks = 0
        while i < len(lines) and lines[i].startswith("@@ "):
            match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@\n", lines[i])
            if not match:
                raise ValueError("invalid patch hunk")
            old_start, old_count, new_start, new_count = (
                int(match[1]), int(match[2] or 1), int(match[3]), int(match[4] or 1))
            offset = old_start - 1 if old_count else old_start
            if offset < position or offset > len(source):
                raise ValueError("invalid or overlapping hunk offset")
            output.extend(source[position:offset])
            position = offset
            if (new_start - 1 if new_count else new_start) != len(output):
                raise ValueError("inconsistent new hunk offset")
            i += 1
            consumed = produced = 0
            while consumed < old_count or produced < new_count:
                if i >= len(lines) or lines[i][:1] not in (" ", "+", "-"):
                    raise ValueError("incomplete patch hunk")
                prefix, value = lines[i][0], lines[i][1:]
                i += 1
                if prefix != "+":
                    if position >= len(source) or source[position] != value:
                        raise ValueError(f"patch context mismatch: {name}")
                    position += 1
                    consumed += 1
                if prefix != "-":
                    output.append(value)
                    produced += 1
                if consumed > old_count or produced > new_count:
                    raise ValueError("patch hunk count mismatch")
            hunks += 1
        if not hunks:
            raise ValueError("patch file has no hunks")
        output.extend(source[position:])
        if new == "/dev/null":
            if output:
                raise ValueError("deletion leaves file content")
            del result[name]
        else:
            result[name] = "".join(output).encode("utf-8")
    return result


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def load_base(base):
    if not re.fullmatch(r"[a-f0-9]{40}", base["commit"]):
        raise ValueError("base must pin a full Git commit")
    data = git("archive", f'{base["commit"]}:docs/agents')
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            if member.isdir():
                continue
            if not member.isfile():
                raise ValueError("base graph contains a special file")
            files[safe_path(member.name)] = archive.extractfile(member).read()
    if content_hash(files) != base["contentHash"]:
        raise ValueError("base graph content hash mismatch")
    return files


def reconstruct(catalog, history=HISTORY):
    if catalog["schemaVersion"] != 1:
        raise ValueError("unsupported history schema")
    graphs = {"base": load_base(catalog["base"])}
    versions = set()
    for revision in catalog["revisions"]:
        name = revision["id"]
        if name in graphs or revision["parent"] not in graphs:
            raise ValueError("duplicate revision or non-topological lineage")
        patch_name = revision["patch"]
        if patch_name != f"patches/{name}.patch" or not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]*", name):
            raise ValueError("invalid revision or patch path")
        patch = (history / patch_name).read_bytes()
        if sha(patch) != revision["patchSha256"]:
            raise ValueError(f"patch checksum mismatch: {name}")
        files = apply_patch(graphs[revision["parent"]], patch)
        if content_hash(files) != revision["contentHash"]:
            raise ValueError(f"graph content hash mismatch: {name}")
        if not revision["versions"]:
            raise ValueError("revision has no SDK versions")
        for version in revision["versions"]:
            if version["version"] in versions or not re.fullmatch(r"[0-9][0-9A-Za-z.+_-]*", version["version"]):
                raise ValueError("duplicate or invalid SDK version")
            if not re.fullmatch(r"[a-f0-9]{40}", version["sdkCommit"]):
                raise ValueError("SDK version must pin its source commit")
            versions.add(version["version"])
        graphs[name] = files
    return graphs


def write_graph(files, directory):
    for name, data in files.items():
        output = directory / safe_path(name)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)


def build_history(catalog, graphs, output, source_commit, selected=None):
    built = []
    requested = set(selected or ())
    known = {v["version"] for r in catalog["revisions"] for v in r["versions"]}
    if requested - known:
        raise ValueError(f"unknown historical SDK versions: {sorted(requested - known)}")
    for revision in catalog["revisions"]:
        with tempfile.TemporaryDirectory(prefix="fluxzero-agent-graph-") as temporary:
            graph = Path(temporary)
            write_graph(graphs[revision["id"]], graph)
            package["validate"](graph)
            for version in revision["versions"]:
                number = version["version"]
                if selected and number not in requested:
                    continue
                requested.discard(number)
                artifact = output / number / f"fluxzero-sdk-java-{number}-agent-docs.zip"
                package["build"](graph, number, source_commit, artifact)
                provenance = {
                    "schemaVersion": 1, "version": number, "sdkCommit": version["sdkCommit"],
                    "documentationCommit": source_commit, "base": catalog["base"],
                    "revision": revision["id"], "contentHash": revision["contentHash"],
                    "artifactSha256": sha(artifact.read_bytes()), "manualSetHash": revision["manualSetHash"],
                }
                artifact.with_suffix(".provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
                built.append(provenance)
    output.mkdir(parents=True, exist_ok=True)
    (output / "inventory.json").write_text(json.dumps(built, indent=2) + "\n")
    return built


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "target/agent-docs-backfill")
    parser.add_argument("--version", action="append", help="Build only these historical versions")
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    catalog = json.loads((HISTORY / "catalog.json").read_text())
    graphs = reconstruct(catalog)
    if args.validate_only:
        for name, files in graphs.items():
            with tempfile.TemporaryDirectory(prefix="fluxzero-agent-graph-") as temporary:
                write_graph(files, Path(temporary))
                package["validate"](Path(temporary))
        print(f"Validated {len(graphs) - 1} historical graph revisions.")
        return
    paths = ["docs/agents-history", ".github/scripts/build-agent-docs-history.py", ".github/scripts/build-agent-docs.py", ".github/scripts/validate-agent-docs.py"]
    if git("status", "--porcelain", "--", *paths).strip():
        raise ValueError("commit the reviewed history and packaging scripts before building release-bound artifacts")
    commit = git("rev-parse", "HEAD").decode().strip()
    built = build_history(catalog, graphs, args.output, commit, args.version)
    print(f"Built {len(built)} historical SDK documentation ZIPs locally in {args.output}.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Cannot build historical agent documentation: {error}")
