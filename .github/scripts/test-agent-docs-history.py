#!/usr/bin/env python3
"""Qualification of historical graph reconstruction, boundaries and release artifacts."""

import copy
import difflib
import json
from pathlib import Path
import runpy
import tempfile
import unittest
import zipfile

api = runpy.run_path(str(Path(__file__).with_name("build-agent-docs-history.py")))


class HistoricalDocsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads((api["HISTORY"] / "catalog.json").read_text())
        cls.graphs = api["reconstruct"](cls.catalog)
        cls.versions = {v["version"]: r["id"] for r in cls.catalog["revisions"] for v in r["versions"]}

    def test_complete_inventory_and_source_pins(self):
        self.assertEqual(391, len(self.versions))
        self.assertEqual(383, sum(v.startswith("1.") for v in self.versions))
        self.assertEqual({f"2.0.0-RC{i}" for i in range(1, 9)}, {v for v in self.versions if v.startswith("2.")})
        self.assertIn("1.75.1", self.versions)
        self.assertIn("1.267.0", self.versions)
        self.assertNotIn("1.268.0", self.versions)
        # Every release points to the actual tagged SDK, separately from the later curated documentation commit.
        tags = api["git"]("show-ref", "--tags").decode().splitlines()
        refs = {line.split()[1]: line.split()[0] for line in tags}
        for revision in self.catalog["revisions"]:
            for version in revision["versions"]:
                ref = f'refs/tags/{version["version"]}'
                commit = api["git"]("rev-parse", f"{ref}^{{commit}}").decode().strip()
                self.assertIn(ref, refs)
                self.assertEqual(commit, version["sdkCommit"])

    def test_exact_patch_refuses_stale_input_and_path_escape(self):
        patch = b"--- a/articles/a.md\n+++ b/articles/a.md\n@@ -1 +1 @@\n-old\n+new\n"
        self.assertEqual({"articles/a.md": b"new\n"}, api["apply_patch"]({"articles/a.md": b"old\n"}, patch))
        for source in (b"different\n", b"prefix\nold\n"):
            with self.assertRaisesRegex(ValueError, "context mismatch"):
                api["apply_patch"]({"articles/a.md": source}, patch)
        with self.assertRaisesRegex(ValueError, "unsafe graph path"):
            api["apply_patch"]({}, b"--- /dev/null\n+++ b/../escape.md\n@@ -0,0 +1 @@\n+x\n")
        with self.assertRaises(ValueError):
            api["apply_patch"]({"articles/a.md": b"old\n"}, patch.replace(b"@@ -1 +1 @@", b"@@ -1,2 +1 @@"))

    def test_add_delete_and_multiple_hunks_preserve_unmodified_files(self):
        old = "".join(f"line {i}\n" for i in range(30))
        new = old.replace("line 2\n", "replacement\n").replace("line 25\n", "later\n")
        patch = "".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True), fromfile="a/articles/a.md", tofile="b/articles/a.md"))
        patch += "--- /dev/null\n+++ b/articles/new.md\n@@ -0,0 +1 @@\n+created\n"
        patch += "--- a/articles/delete.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n"
        files = {"articles/a.md": old.encode(), "articles/delete.md": b"gone\n", "manifest.json": b"unchanged"}
        self.assertEqual({"articles/a.md": new.encode(), "articles/new.md": b"created\n", "manifest.json": b"unchanged"}, api["apply_patch"](files, patch.encode()))
        self.assertIn("articles/delete.md", files)

    def test_tampered_catalog_and_duplicate_versions_fail(self):
        for change in ("patch", "content", "duplicate", "lineage"):
            catalog = copy.deepcopy(self.catalog)
            first = catalog["revisions"][0]
            if change == "patch":
                first["patchSha256"] = "0" * 64
            elif change == "content":
                first["contentHash"] = "0" * 64
            elif change == "duplicate":
                first["versions"].append(first["versions"][0])
            else:
                first["parent"] = first["id"]
            with self.subTest(change=change), self.assertRaises(ValueError):
                api["reconstruct"](catalog)

    def test_code_boundaries_inside_unchanged_manual_sets(self):
        cases = [
            ("1.75.1", "1.75.2", "expectNoScheduleLike"),
            ("1.81.0", "1.82.1", "enableHostMetrics()"),
            ("1.162.5", "1.163.0", "AggregateEventRouting"),
            ("1.165.0", "1.165.1", "RefreshingUserProvider"),
            ("1.165.1", "1.166.0", "FLUXZERO_CONFIG_LOCATIONS"),
            ("1.167.0", "1.169.0", "getViolationSummaries()"),
            ("1.170.0", "1.171.0", "Fluxzero.bulkUpdate("),
            ("1.178.0", "1.179.0", "fluxzero.defaults.version"),
            ("1.181.0", "1.182.0", "expectOnlyScheduledCommands("),
            ("1.183.0", "1.184.0", "readRange("),
            ("1.196.0", "1.201.0", "awaitAsyncResults"),
        ]
        for older, newer, symbol in cases:
            with self.subTest(symbol=symbol):
                old = b"\n".join(self.graphs[self.versions[older]].values()).decode()
                new = b"\n".join(self.graphs[self.versions[newer]].values()).decode()
                self.assertNotIn(symbol, old)
                self.assertIn(symbol, new)
        # Published prereleases have three distinct persistence APIs, not one interchangeable Model annotation.
        for version, present, absent in [
            ("2.0.0-RC3", "@Model(searchable = true)", "ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT"),
            ("2.0.0-RC4", "ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT", "ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT"),
            ("2.0.0-RC8", "ModelPersistence.EVENT_SOURCED, ModelPersistence.DOCUMENT", "@Model(searchable = true)"),
        ]:
            manifest = json.loads(self.graphs[self.versions[version]]["manifest.json"])
            source = next(a["source"] for a in manifest["articles"] if a["path"] == "/docs/sdk/entities")
            text = self.graphs[self.versions[version]][source].decode()
            self.assertIn(present, text)
            self.assertNotIn(absent, text)

    def test_packaging_is_deterministic_and_records_both_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / "first", Path(temporary) / "second"
            commit = self.catalog["base"]["commit"]
            selected = ["1.75.1", "1.267.0", "2.0.0-RC1", "2.0.0-RC8"]
            result = api["build_history"](self.catalog, self.graphs, first, commit, selected)
            api["build_history"](self.catalog, self.graphs, second, commit, selected)
            self.assertEqual(4, len(result))
            for artifact in first.rglob("*.zip"):
                self.assertEqual(artifact.read_bytes(), (second / artifact.relative_to(first)).read_bytes())
                self.assertEqual(api["sha"](artifact.read_bytes()), artifact.with_suffix(".zip.sha256").read_text().strip())
                provenance = json.loads(artifact.with_suffix(".provenance.json").read_text())
                with zipfile.ZipFile(artifact) as archive:
                    release = json.loads(archive.read("release.json"))
                    self.assertEqual(provenance["version"], release["componentVersion"])
                    self.assertEqual(commit, release["sourceCommit"])
                    self.assertEqual(provenance["contentHash"], release["contentHash"])
                    self.assertEqual(release["contentHash"], api["content_hash"]({n: archive.read(n) for n in archive.namelist() if n != "release.json"}))
            with self.assertRaisesRegex(ValueError, "unknown historical"):
                api["build_history"](self.catalog, self.graphs, Path(temporary) / "missing", commit, ["1.0.0"])
            self.assertFalse((Path(temporary) / "missing").exists())


if __name__ == "__main__":
    unittest.main()
