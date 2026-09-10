"""Publication-byte preservation and failure boundaries; no live uploads."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess

spec = importlib.util.spec_from_file_location("assets", Path(__file__).with_name("collect-maven-assets.py"))
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)

POM = "io/fluxzero/example/1.2.3/example-1.2.3.pom"
JAR = "io/fluxzero/example/1.2.3/example-1.2.3-tests.jar"
SBOM = "io/fluxzero/example/1.2.3/example-1.2.3-cyclonedx.json"
MODULE = "io/fluxzero/tools/plugin/1.2.3/plugin-1.2.3.module"


def repository(paths):
    result = {}
    for path in paths:
        for suffix in ("", ".asc"):
            key = path + suffix
            result[key] = b"published bytes, not a local rebuild: " + key.encode()
            # Maven does not normally publish checksum sidecars for signatures.
            if not suffix:
                for algorithm in assets.CHECKSUMS:
                    result[key + "." + algorithm] = hashlib.new(algorithm, result[key]).hexdigest().encode()
    return result


class ReleaseAssetsTest(unittest.TestCase):
    def collect(self, paths, remote):
        def fetch(path, destination, optional=False):
            if optional and path not in remote:
                return False
            destination.write_bytes(remote[path])
            return True
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "assets"
            assets.collect(paths, output, fetch)
            return {p.name: p.read_bytes() for p in output.iterdir()}

    def test_completed_maven_transfers_only(self):
        lines = [f"[INFO] Uploaded to fluxzero: {assets.PUBLISH_URL}{p} (12 kB at 1 kB/s)"
                 for p in (POM, JAR, SBOM, POM + ".asc", POM)]
        lines += [f"Uploading to fluxzero: {assets.PUBLISH_URL}io/fluxzero/incomplete/1/incomplete-1.jar",
                  f"Uploaded to fluxzero: {assets.PUBLISH_URL}io/fluxzero/example/maven-metadata.xml",
                  "Downloaded from central: https://repo.maven.apache.org/maven2/third-party.jar"]
        log = "\x1b[0m" + "\n".join(lines)
        self.assertEqual(sorted(set((POM, JAR, SBOM, POM + ".asc"))), assets.publication_paths(log))

    def test_exact_bytes_for_multiple_modules_and_extra_attachments(self):
        paths = [POM, JAR, SBOM, MODULE]
        remote = repository(paths)
        self.assertEqual({Path(p).name: data for p, data in remote.items()}, self.collect(paths, remote))

    def test_maven_without_optional_checksums(self):
        remote = repository([POM])
        del remote[POM + ".sha256"]
        del remote[POM + ".sha512"]
        self.assertEqual(4, len(self.collect([POM], remote)))

    def test_missing_signature_or_required_checksum_fails(self):
        for suffix in (".asc", ".sha1", ".md5"):
            with self.subTest(suffix=suffix):
                remote = repository([POM])
                del remote[POM + suffix]
                with self.assertRaises(KeyError):
                    self.collect([POM], remote)

    def test_explicitly_uploaded_checksum_cannot_be_missing(self):
        remote = repository([POM])
        del remote[POM + ".sha256"]
        with self.assertRaises(KeyError):
            self.collect([POM, POM + ".sha256"], remote)

    def test_corruption_fails(self):
        remote = repository([POM])
        remote[POM] = b"different bytes"
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self.collect([POM], remote)

    def test_optional_http_404_only_is_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / "checksum"
            for exit_code in (22, 56):
                result = subprocess.CompletedProcess([], exit_code, "404", "not found")
                with patch.object(assets.subprocess, "run", return_value=result):
                    self.assertFalse(assets.download(POM + ".sha256", target, optional=True))
                    with self.assertRaises(RuntimeError):
                        assets.download(POM, target)
            for status in ("403", "500", "000"):
                result = subprocess.CompletedProcess([], 22, status, "failure")
                with patch.object(assets.subprocess, "run", return_value=result):
                    with self.assertRaises(RuntimeError):
                        assets.download(POM + ".sha256", target, optional=True)

    def test_failure_does_not_leave_partial_upload_directory(self):
        def fail(path, destination, optional=False):
            if path.endswith(".asc"):
                raise RuntimeError("missing signature")
            if optional:
                return False
            if path.endswith(".md5"):
                destination.write_text(hashlib.md5(b"pom").hexdigest())
            elif path.endswith(".sha1"):
                destination.write_text(hashlib.sha1(b"pom").hexdigest())
            else:
                destination.write_bytes(b"pom")
            return True
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "assets"
            with self.assertRaises(RuntimeError):
                assets.collect([POM], output, fail)
            self.assertFalse(output.exists())

    def test_empty_log_path_traversal_snapshots_and_collisions_fail(self):
        with self.assertRaises(ValueError):
            assets.publication_paths("BUILD SUCCESS without publication")
        for paths in ([POM.replace("example/1.2.3", "../1.2.3")],
                      [POM.replace("1.2.3", "1-SNAPSHOT")],
                      [POM, POM.replace("io/fluxzero/", "io/fluxzero/other/")]):
            with self.subTest(paths=paths), self.assertRaises(ValueError):
                assets.validate_paths(paths)


if __name__ == "__main__":
    unittest.main()
