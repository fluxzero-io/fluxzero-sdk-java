#!/usr/bin/env python3
"""Resolve automatic stable versions from reachable tags and Conventional Commits."""
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def next_version(major):
    stable = re.compile(rf"{major}\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
    tags = []
    for tag in git("tag", "--merged", "HEAD").splitlines():
        match = stable.fullmatch(tag)
        if match:
            tags.append((int(match[1]), int(match[2]), tag))
    if not tags:
        version = f"{major}.0.0"
    else:
        minor, patch, base = max(tags)
        if git("rev-parse", f"refs/tags/{base}^{{commit}}") == git("rev-parse", "HEAD"):
            return base  # Reruns reuse the immutable version on this commit.
        level = 0
        for message in git("log", "--format=%B%x00", f"{base}..HEAD").split("\0"):
            message = message.strip()
            if not message:
                continue
            subject = message.splitlines()[0]
            header = re.match(r"^([a-z]+)(?:\([^\r\n)]*\))?(!)?:\s+", subject)
            if (header and header[2]) or re.search(r"^BREAKING[ -]CHANGE:\s*", message, re.MULTILINE):
                raise ValueError("Breaking change requires an explicit major-release transition; "
                                 "update .github/release-major before publishing")
            if header:
                if header[1] == "feat":
                    level = max(level, 2)
                elif header[1] in {"fix", "perf", "deps", "revert"}:
                    level = max(level, 1)
            elif subject.startswith('Revert "'):
                level = max(level, 1)
        # Preserve the previous tag-action's minor fallback for unclassified changes.
        version = f"{major}.{minor}.{patch + 1}" if level == 1 else f"{major}.{minor + 1}.0"
    if version in git("tag", "--list").splitlines():
        raise ValueError(f"Version {version} already exists outside the release history")
    return version


if __name__ == "__main__":
    try:
        print(next_version(int(sys.argv[1])))
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
