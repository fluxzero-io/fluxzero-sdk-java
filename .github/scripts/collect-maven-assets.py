#!/usr/bin/env python3
"""Collect exact Packages publication bytes as flat GitHub Release assets."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile

PUBLISH_URL = "https://packages.fluxzero.io/publish/maven/"
DOWNLOAD_URL = "https://packages.fluxzero.io/maven/"
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")


def publication_paths(log):
    # Maven logs the completed artifact transfers, including attached signatures.
    paths = set(re.findall(r"Uploaded to fluxzero: " + re.escape(PUBLISH_URL) + r"([^\s]+)",
                           re.sub(r"\x1b\[[0-9;]*m", "", log)))
    paths = {p for p in paths if not PurePosixPath(p).name.startswith("maven-metadata")}
    if not paths or not any(p.endswith(".pom") for p in paths):
        raise ValueError("No completed Maven publication found in deploy log")
    return sorted(paths)


def validate_paths(paths):
    names = set()
    for path in paths:
        parts = PurePosixPath(path).parts
        if (not path.startswith("io/fluxzero/") or len(parts) < 5
                or any(not re.fullmatch(r"[A-Za-z0-9_.+-]+", p) or p in (".", "..") for p in parts)
                or str(PurePosixPath(path)) != path or "SNAPSHOT" in parts[-2]):
            raise ValueError(f"Invalid release path: {path}")
        if parts[-1] in names:
            raise ValueError(f"GitHub asset filename collision: {parts[-1]}")
        names.add(parts[-1])


def download(path, destination, optional=False):
    result = subprocess.run([
        "curl", "--silent", "--show-error", "--location", "--fail",
        "--retry", "4", "--retry-delay", "2", "--connect-timeout", "15",
        "--max-time", "120", "--output", str(destination), "--write-out", "%{http_code}",
        DOWNLOAD_URL + path,
    ], capture_output=True, text=True)
    if optional and result.stdout == "404":
        destination.unlink(missing_ok=True)
        return False
    if result.returncode or result.stdout != "200":
        raise RuntimeError(f"Download failed for {path}: HTTP {result.stdout}; {result.stderr.strip()}")
    return True


def collect(paths, output, fetch=download):
    paths = sorted(set(paths))
    validate_paths(paths)
    # Signatures must come from Packages too, never from a later Central rebuild.
    files = {p for p in paths if not p.endswith(tuple("." + h for h in CHECKSUMS))}
    files |= {p + ".asc" for p in files if not p.endswith(".asc")}
    validate_paths(sorted(files))
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"Output directory must be empty: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as staging:
        staging = Path(staging)
        for path in sorted(files):
            target = staging / PurePosixPath(path).name
            fetch(path, target)
            for algorithm in CHECKSUMS:
                checksum_path = path + "." + algorithm
                checksum = staging / (target.name + "." + algorithm)
                optional = checksum_path not in paths and (path.endswith(".asc") or algorithm in ("sha256", "sha512"))
                if fetch(checksum_path, checksum, optional=optional):
                    actual = hashlib.new(algorithm, target.read_bytes()).hexdigest()
                    if checksum.read_text().strip().lower() != actual:
                        raise ValueError(f"Checksum mismatch: {checksum_path}")
        output.mkdir(exist_ok=True)
        for item in staging.iterdir():
            item.replace(output / item.name)
    print(f"Collected {len(list(output.iterdir()))} exact Maven release assets")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--deploy-log", type=Path)
    source.add_argument("--paths", nargs="+")
    parser.add_argument("--output", type=Path, default=Path("maven-release-assets"))
    args = parser.parse_args()
    paths = publication_paths(args.deploy_log.read_text()) if args.deploy_log else args.paths
    collect(paths, args.output)


if __name__ == "__main__":
    main()
