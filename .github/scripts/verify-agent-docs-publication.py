#!/usr/bin/env python3
"""Verify a temporary Maven publication contains the exact graph archive and checksum."""

import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(repository: Path) -> None:
    artifact_root = repository / "io/fluxzero/fluxzero-sdk-java"
    archives = list(artifact_root.glob("*/*-agent-docs.zip"))
    if len(archives) != 1:
        raise ValueError(f"expected one published agent graph ZIP, found {len(archives)}")
    archive = archives[0]
    version = archive.parent.name
    built = Path(__file__).resolve().parents[2] / "target" / f"fluxzero-sdk-java-{version}-agent-docs.zip"
    if archive.read_bytes() != built.read_bytes():
        raise ValueError("Maven publication differs from the ZIP used for the GitHub release")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    for file in [archive, built]:
        if file.with_suffix(".zip.sha256").read_text(encoding="ascii").strip() != digest:
            raise ValueError(f"incorrect or missing SHA-256 sidecar for {file}")
    with zipfile.ZipFile(archive) as bundle:
        release = json.loads(bundle.read("release.json"))
        if release["componentVersion"] != version or release["namespace"] != "sdk":
            raise ValueError("release metadata does not match the Maven coordinates")
    print(f"Published {archive.name}: same ZIP bytes, matching version/namespace and SHA-256 sidecars.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: verify-agent-docs-publication.py <temporary-maven-repository>")
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(f"Agent documentation publication is invalid: {error}")
