#!/usr/bin/env python3
"""Compare paired JMH JSON runs and fail on material scenario regressions."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import statistics
import sys
from pathlib import Path


SCENARIO_UNITS = {
    "aggregateLifecycle": "lifecycle (50 replay + 3 apply)",
    "localCommandHandling": "command",
    "localSelfHandlingQueries": "self-handled query",
    "localStandaloneQueryHandling": "query handled by a multi-method local handler",
    "outboundDispatch": "event in a 32-event batch",
    "selfTrackedCommandHandling": "self-tracked command in a 32-command batch",
    "standaloneTrackedCommandHandling": "command in a 32-command tracked batch",
    "standaloneTrackedEventHandling": "entity-resolving event in a 32-event tracked batch",
    "statefulAndStandaloneTrackedEventHandling": "event in a 32-event tracked batch",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", action="append", required=True, type=Path)
    parser.add_argument("--head", action="append", required=True, type=Path)
    parser.add_argument("--time-threshold", type=float, default=0.15)
    parser.add_argument("--allocation-threshold", type=float, default=0.15)
    parser.add_argument("--allocation-min-bytes", type=float, default=256.0)
    return parser.parse_args()


def load_runs(paths: list[Path]) -> dict[str, list[dict]]:
    result: dict[str, list[dict]] = {}
    for path in paths:
        with path.open(encoding="utf-8") as source:
            run = json.load(source)
        for entry in run:
            result.setdefault(entry["benchmark"], []).append(entry)
    return result


def mean_metric(entries: list[dict], metric: str | None = None) -> tuple[float, str]:
    if metric is None:
        values = [entry["primaryMetric"]["score"] for entry in entries]
        units = {entry["primaryMetric"]["scoreUnit"] for entry in entries}
    else:
        values = [entry["secondaryMetrics"][metric]["score"] for entry in entries]
        units = {entry["secondaryMetrics"][metric]["scoreUnit"] for entry in entries}
    if len(units) != 1:
        raise ValueError(f"Inconsistent metric units: {sorted(units)}")
    return statistics.fmean(values), units.pop()


def short_name(benchmark: str) -> str:
    parts = benchmark.split(".")
    return ".".join(parts[-2:])


def scenario_name(benchmark: str) -> str:
    method_name = benchmark.rsplit(".", 1)[-1]
    return re.sub(r"(?<!^)(?=[A-Z])", " ", method_name).capitalize()


def scenario_unit(benchmark: str) -> str:
    return SCENARIO_UNITS.get(benchmark.rsplit(".", 1)[-1], "invocation")


def delta(before: float, after: float) -> float:
    if before == 0.0:
        return 0.0 if after == 0.0 else float("inf")
    return after / before - 1.0


def percent(value: float) -> str:
    return f"{value:+.1%}"


def main() -> int:
    args = parse_args()
    if len(args.base) != 2 or len(args.head) != 2:
        raise ValueError("Exactly two base and two head runs are required for the ABBA comparison")
    base = load_runs(args.base)
    head = load_runs(args.head)
    if not base or not head:
        raise ValueError("No benchmark results were produced")
    if base.keys() != head.keys():
        missing_from_head = sorted(base.keys() - head.keys())
        missing_from_base = sorted(head.keys() - base.keys())
        raise ValueError(
            f"Benchmark sets differ; missing from head={missing_from_head}, missing from base={missing_from_base}"
        )

    summary_rows: list[str] = []
    detail_rows: list[str] = []
    failures: list[str] = []
    for benchmark in sorted(base):
        if len(base[benchmark]) != 2 or len(head[benchmark]) != 2:
            raise ValueError(f"Expected two base and two head results for {benchmark}")
        base_time, time_unit = mean_metric(base[benchmark])
        head_time, head_time_unit = mean_metric(head[benchmark])
        if time_unit != head_time_unit:
            raise ValueError(f"Time units differ for {benchmark}: {time_unit} vs {head_time_unit}")

        allocation_metric = "gc.alloc.rate.norm"
        base_alloc, allocation_unit = mean_metric(base[benchmark], allocation_metric)
        head_alloc, head_allocation_unit = mean_metric(head[benchmark], allocation_metric)
        if allocation_unit != head_allocation_unit:
            raise ValueError(
                f"Allocation units differ for {benchmark}: {allocation_unit} vs {head_allocation_unit}"
            )
        values = (base_time, head_time, base_alloc, head_alloc)
        if not all(math.isfinite(value) for value in values):
            raise ValueError(f"Non-finite metric detected for {benchmark}")
        if base_time <= 0.0 or head_time <= 0.0 or base_alloc < 0.0 or head_alloc < 0.0:
            raise ValueError(f"Invalid metric detected for {benchmark}")

        time_delta = delta(base_time, head_time)
        allocation_delta = delta(base_alloc, head_alloc)
        time_regression = time_delta > args.time_threshold
        allocation_regression = (
            allocation_delta > args.allocation_threshold
            and head_alloc - base_alloc >= args.allocation_min_bytes
        )
        failed = time_regression or allocation_regression
        status = "❌ Regression" if failed else "✅ Pass"
        name = scenario_name(benchmark)
        operation = scenario_unit(benchmark)
        time_change = percent(time_delta)
        allocation_change = percent(allocation_delta)
        summary_rows.append(
            f"| {name} | {time_change} | {allocation_change} | {status} |"
        )
        detail_rows.append(
            f"| {name} | {operation} | {base_time:.3f} → {head_time:.3f} | {time_change} | "
            f"{base_alloc:.0f} → {head_alloc:.0f} | {allocation_change} |"
        )
        if time_regression:
            failures.append(
                f"{short_name(benchmark)} time increased by {percent(time_delta)} "
                f"(limit {args.time_threshold:.0%})"
            )
        if allocation_regression:
            failures.append(
                f"{short_name(benchmark)} allocation increased by {percent(allocation_delta)} "
                f"and {head_alloc - base_alloc:.0f} B/op "
                f"(limits {args.allocation_threshold:.0%} and {args.allocation_min_bytes:.0f} B/op)"
            )

    headline = "❌ Performance regression gate failed" if failures else "✅ Performance regression gate passed"
    outcome = (
        "1 regression criterion was exceeded."
        if len(failures) == 1
        else f"{len(failures)} regression criteria were exceeded."
        if failures
        else "No material regression was detected against the pull-request base."
    )
    report = "\n".join(
        [
            f"## {headline}",
            "",
            outcome,
            "",
            "| Hot-path scenario | Time vs base | Allocation vs base | Result |",
            "| --- | ---: | ---: | :---: |",
            *summary_rows,
            "",
            "<details>",
            "<summary>Measurement details and regression limits</summary>",
            "",
            "Lower is better. Base and head are the means of two ABBA-ordered JMH runs on the same machine.",
            "",
            f"The gate fails above **+{args.time_threshold:.0%} time**, or above "
            f"**+{args.allocation_threshold:.0%} allocation** with at least "
            f"**+{args.allocation_min_bytes:.0f} B/op**.",
            "",
            f"| Hot-path scenario | Measured operation | Time: base → head ({time_unit}) | Change | "
            f"Allocation: base → head ({allocation_unit}) | Change |",
            "| --- | --- | ---: | ---: | ---: | ---: |",
            *detail_rows,
            "",
            "</details>",
        ]
    )
    if failures:
        report += "\n\n### Regressions\n\n" + "\n".join(f"- {failure}" for failure in failures)
        if os.environ.get("GITHUB_ACTIONS"):
            for failure in failures:
                print(f"::error title=Performance regression::{failure}")

    print(report)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(report + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Could not compare benchmark results: {error}", file=sys.stderr)
        sys.exit(2)
