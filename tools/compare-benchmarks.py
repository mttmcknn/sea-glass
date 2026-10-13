#!/usr/bin/env python3
"""Validate matching renderer workloads/pixels and print a Markdown timing table."""

import argparse
import json
import math
import statistics
from pathlib import Path


def load_report(path):
    with path.open(encoding="utf-8") as source:
        report = json.load(source)
    if not isinstance(report, dict):
        raise ValueError(f"{path}: expected a JSON object")
    for field in ("workloadVersion", "scale", "samples", "warmupsPerCase", "cases"):
        if field not in report:
            raise ValueError(f"{path}: missing {field}")
    for field, minimum in (("workloadVersion", 1), ("samples", 1), ("warmupsPerCase", 0)):
        value = report[field]
        if type(value) is not int or value < minimum:
            raise ValueError(f"{path}: invalid {field}")
    if not isinstance(report["scale"], (int, float)) or not math.isfinite(report["scale"]) or report["scale"] <= 0:
        raise ValueError(f"{path}: invalid scale")
    if not isinstance(report["cases"], list) or not report["cases"]:
        raise ValueError(f"{path}: expected a nonempty case list")
    names = set()
    for case in report["cases"]:
        if not isinstance(case, dict):
            raise ValueError(f"{path}: expected case objects")
        for field in ("name", "componentCount", "fixtures", "checksums", "renderNs"):
            if field not in case:
                raise ValueError(f"{path}: case is missing {field}")
        name = case["name"]
        if not isinstance(name, str) or not name or name in names:
            raise ValueError(f"{path}: case names must be nonempty and unique")
        names.add(name)
        if not isinstance(case["fixtures"], list) or not case["fixtures"] or case["componentCount"] != len(case["fixtures"]):
            raise ValueError(f"{path}: {name} has invalid fixtures/componentCount")
        for field in ("checksums", "renderNs"):
            if not isinstance(case[field], list) or len(case[field]) != report["samples"]:
                raise ValueError(f"{path}: {name}.{field} must contain {report['samples']} samples")
        if any(not isinstance(checksum, str) or not checksum.lstrip("-").isdigit() for checksum in case["checksums"]):
            raise ValueError(f"{path}: {name} has invalid pixel checksums")
        if any(type(value) is not int or value <= 0 for value in case["renderNs"]):
            raise ValueError(f"{path}: {name} has invalid render timing samples")
    return report


def compare(before, after):
    for field in ("workloadVersion", "scale", "samples", "warmupsPerCase"):
        if before[field] != after[field]:
            raise ValueError(f"Incompatible {field}: {before[field]!r} vs {after[field]!r}")
    before_names = [case["name"] for case in before["cases"]]
    after_names = [case["name"] for case in after["cases"]]
    if before_names != after_names:
        raise ValueError("Case names or ordering differ")
    for previous, current in zip(before["cases"], after["cases"]):
        for field in ("fixtures", "componentCount"):
            if previous[field] != current[field]:
                raise ValueError(f"{previous['name']}: incompatible {field}")
        for index, (old, new) in enumerate(zip(previous["checksums"], current["checksums"])):
            if old != new:
                raise ValueError(f"{previous['name']}: pixel checksum differs at sample {index + 1}")


def timing(samples):
    ordered = sorted(samples)
    # Recompute statistics from raw timings instead of trusting cached summaries.
    return statistics.median(ordered) / 1_000_000, ordered[math.ceil(len(ordered) * 0.95) - 1] / 1_000_000


def markdown(before, after):
    count = len(before["cases"]) * before["samples"]
    lines = [
        f"Matching workloads; all {count} pixel checksums match. CPU rendering in milliseconds; speedup uses the median.",
        "",
        "| Case | Before median | After median | Before p95 | After p95 | Speedup |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for previous, current in zip(before["cases"], after["cases"]):
        old_median, old_p95 = timing(previous["renderNs"])
        new_median, new_p95 = timing(current["renderNs"])
        name = previous["name"].replace("|", "\\|").replace("\n", " ").replace("\r", " ")
        lines.append(f"| {name} | {old_median:.2f} | {new_median:.2f} | {old_p95:.2f} | {new_p95:.2f} | {old_median / new_median:.2f}× |")
    lines.extend(["", "These are JVM CPU measurements, not Android frame timings. Native bitmap conversion is excluded."])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path, help="Baseline benchmark JSON")
    parser.add_argument("after", type=Path, help="Candidate benchmark JSON")
    args = parser.parse_args()
    try:
        before, after = load_report(args.before), load_report(args.after)
        compare(before, after)
        print(markdown(before, after))
    except (OSError, ValueError) as error:
        parser.exit(2, f"Benchmark comparison failed: {error}\n")


if __name__ == "__main__":
    main()
