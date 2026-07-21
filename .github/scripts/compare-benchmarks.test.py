#!/usr/bin/env python3

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


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
            environment = os.environ.copy()
            environment.pop("GITHUB_ACTIONS", None)
            environment.pop("GITHUB_STEP_SUMMARY", None)
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
                env=environment,
            )

    def test_equal_results_pass(self):
        benchmark = [result("example.Scenario.run", 100.0, 1_000.0)]
        comparison = self.compare(benchmark, benchmark)
        self.assertEqual(0, comparison.returncode)
        self.assertIn("✅ Performance regression gate passed", comparison.stdout)

    def test_time_regression_fails(self):
        comparison = self.compare(
            [result("example.Scenario.run", 100.0, 1_000.0)],
            [result("example.Scenario.run", 116.0, 1_000.0)],
        )
        self.assertEqual(1, comparison.returncode)
        self.assertIn("❌ Performance regression gate failed", comparison.stdout)
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

    def test_known_scenario_describes_the_normalized_operation(self):
        benchmark = [result(
            "io.fluxzero.sdk.tracking.TrackingHotPathBenchmark.trackedHandlingRoutes",
            100.0,
            1_000.0,
        )]
        comparison = self.compare(benchmark, benchmark)
        self.assertEqual(0, comparison.returncode)
        self.assertIn("message across four representative tracked route batches", comparison.stdout)

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

    def test_test_comparisons_do_not_write_the_github_summary(self):
        benchmark = [result("example.Scenario.run", 100.0, 1_000.0)]
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            with patch.dict(
                os.environ,
                {"GITHUB_ACTIONS": "true", "GITHUB_STEP_SUMMARY": str(summary)},
            ):
                self.assertEqual(0, self.compare(benchmark, benchmark).returncode)
            self.assertFalse(summary.exists())


if __name__ == "__main__":
    unittest.main()
