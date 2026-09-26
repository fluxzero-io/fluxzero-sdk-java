#!/usr/bin/env python3
"""Select lightweight documentation validation from a complete Git diff."""
import os
from pathlib import PurePosixPath
import re
import subprocess
import sys


TEXT_SUFFIXES = {".md", ".mdx", ".markdown", ".rst", ".adoc"}
DOC_ASSET_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".pdf", ".mmd", ".drawio"}


def is_documentation(path):
    file = PurePosixPath(os.fsdecode(path))
    # Source resources and fixtures can affect runtime or test behavior without being compiled.
    if "src" in file.parts or "fixtures" in file.parts:
        return False
    if file.name.lower() in {"requirements.txt", "constraints.txt", "cmakelists.txt"}:
        return False
    return (file.suffix.lower() in TEXT_SUFFIXES
            or path in {b"LICENSE", b"NOTICE", b"docs/agents/manifest.json"}
            or (file.name.lower() in {"readme.txt", "license.txt", "notice.txt", "changelog.txt"})
            or (path.startswith(b"docs/") and file.suffix.lower() == ".txt")
            or (path.startswith(b"docs/") and file.suffix.lower() in DOC_ASSET_SUFFIXES))


def classify(base, head):
    if not all(re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", ref) for ref in (base, head)):
        raise ValueError("Expected full base and head commit hashes")
    changed = subprocess.check_output([
        "git", "diff", "--name-only", "--no-renames", "-z", base, head, "--"
    ])
    # NUL framing handles unusual filenames; disabling rename detection includes both paths.
    paths = changed.rstrip(b"\0").split(b"\0") if changed else []
    return {
        "documentation_only": bool(paths) and all(is_documentation(path) for path in paths),
        "website_changed": any(path.startswith(b"docs/developer/") for path in paths),
    }


if __name__ == "__main__":
    try:
        base, head = sys.argv[1:]
        for key, value in classify(base, head).items():
            print(f"{key}={str(value).lower()}")
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
