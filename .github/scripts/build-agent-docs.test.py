#!/usr/bin/env python3
"""Check the downloadable archive contract without Maven or network access."""

import hashlib
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
import zipfile

build = runpy.run_path(str(Path(__file__).with_name("build-agent-docs.py")))["build"]
COMMIT = "1234567890abcdef" * 2 + "12345678"


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.graph = self.root / "graph"
        (self.graph / "articles").mkdir(parents=True)
        self.article = self.graph / "articles/index.md"
        self.article.write_text("Shared Java and Kotlin guidance.\n", encoding="utf-8")
        self.manifest = {
            "schemaVersion": 1, "namespace": "sdk", "root": "/docs",
            "articles": [{"path": "/docs", "title": "Start", "summary": "Entry point",
                          "source": "articles/index.md", "symbols": [], "links": []}],
        }
        self.write_manifest()
        self.output = self.root / "agent-docs.zip"

    def write_manifest(self):
        (self.graph / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def package(self, version="1.2.3"):
        build(self.graph, version, COMMIT, self.output)
        with zipfile.ZipFile(self.output) as archive:
            return json.loads(archive.read("release.json"))

    def test_archive_contains_exact_graph_and_release_identity(self):
        release = self.package()
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(["articles/index.md", "manifest.json", "release.json"], archive.namelist())
            for name in ["articles/index.md", "manifest.json"]:
                self.assertEqual((self.graph / name).read_bytes(), archive.read(name))
            digest_lines = b"".join(name.encode() + b"\0" + hashlib.sha256(archive.read(name)).hexdigest().encode() + b"\n"
                                    for name in ["articles/index.md", "manifest.json"])
        self.assertEqual({"schemaVersion": 1, "namespace": "sdk", "componentVersion": "1.2.3",
                          "sourceCommit": COMMIT, "contentHash": hashlib.sha256(digest_lines).hexdigest()}, release)
        self.assertEqual(hashlib.sha256(self.output.read_bytes()).hexdigest(),
                         self.output.with_suffix(".zip.sha256").read_text().strip())

    def test_rebuild_ignores_filesystem_time_and_permissions(self):
        self.package()
        original = self.output.read_bytes()
        os.utime(self.article, (1234567890, 1234567890))
        self.article.chmod(0o600)
        self.package()
        self.assertEqual(original, self.output.read_bytes())

    def test_release_and_content_identity_are_independent(self):
        first = self.package()
        new_version = self.package("1.2.4")
        self.assertNotEqual(first["componentVersion"], new_version["componentVersion"])
        self.assertEqual(first["contentHash"], new_version["contentHash"])
        self.article.write_text("Changed guidance.\n", encoding="utf-8")
        self.assertNotEqual(first["contentHash"], self.package()["contentHash"])

    def test_invalid_graph_is_not_published(self):
        self.manifest["articles"][0]["links"] = [{"path": "/docs/missing", "description": "Missing"}]
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "unresolved link"):
            self.package()
        self.assertFalse(self.output.exists())

    def test_unresolved_release_metadata_is_rejected(self):
        for version, commit in [("${project.version}", COMMIT), ("1.2.3", "unknown")]:
            with self.subTest(version=version, commit=commit), self.assertRaises(ValueError):
                build(self.graph, version, commit, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
