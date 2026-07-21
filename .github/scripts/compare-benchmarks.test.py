#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("compare-benchmarks.py")


def result(benchmark: str, time: float, allocation: float) -> dict:
    return {
        "benchmark": benchmark,
        "primaryMetric": {"score": time, "scoreUnit": "us/op"},
        "secondaryMetrics": {
            "gc.alloc.rate.norm": {"score": allocation, "scoreUnit": "B/op"}
        },
    }


class CompareBenchmarksTest(unittest.TestCase):
    def compare(self, base: list[dict], head: list[dict]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            paths = [directory_path / name for name in ("base-1.json", "base-2.json", "head-1.json", "head-2.json")]
            for path, value in zip(paths, (base, base, head, head)):
                path.write_text(json.dumps(value), encoding="utf-8")
            return subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--base", str(paths[0]),
                    "--base", str(paths[1]),
                    "--head", str(paths[2]),
                    "--head", str(paths[3]),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

    def test_equal_results_pass(self):
        benchmark = [result("example.Scenario.run", 100.0, 1_000.0)]
        self.assertEqual(0, self.compare(benchmark, benchmark).returncode)

    def test_time_regression_fails(self):
        comparison = self.compare(
            [result("example.Scenario.run", 100.0, 1_000.0)],
            [result("example.Scenario.run", 116.0, 1_000.0)],
        )
        self.assertEqual(1, comparison.returncode)
        self.assertIn("time increased", comparison.stdout)

    def test_material_allocation_regression_fails(self):
        comparison = self.compare(
            [result("example.Scenario.run", 100.0, 1_000.0)],
            [result("example.Scenario.run", 100.0, 1_300.0)],
        )
        self.assertEqual(1, comparison.returncode)
        self.assertIn("allocation increased", comparison.stdout)

    def test_small_absolute_allocation_change_passes(self):
        comparison = self.compare(
            [result("example.Scenario.run", 100.0, 1_000.0)],
            [result("example.Scenario.run", 100.0, 1_200.0)],
        )
        self.assertEqual(0, comparison.returncode)

    def test_different_benchmark_sets_are_configuration_error(self):
        comparison = self.compare(
            [result("example.Scenario.base", 100.0, 1_000.0)],
            [result("example.Scenario.head", 100.0, 1_000.0)],
        )
        self.assertEqual(2, comparison.returncode)
        self.assertIn("Benchmark sets differ", comparison.stderr)

    def test_non_finite_metric_is_configuration_error(self):
        comparison = self.compare(
            [result("example.Scenario.run", 100.0, 1_000.0)],
            [result("example.Scenario.run", float("nan"), 1_000.0)],
        )
        self.assertEqual(2, comparison.returncode)
        self.assertIn("Non-finite metric", comparison.stderr)


if __name__ == "__main__":
    unittest.main()
