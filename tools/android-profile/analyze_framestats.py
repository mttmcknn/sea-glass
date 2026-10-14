#!/usr/bin/env python3
"""Summarize saved `dumpsys gfxinfo PACKAGE framestats` output; never calls adb.

Example:
  python3 work/analyze_framestats.py work/frost.txt --output work/frost.json

Durations are FrameCompleted - IntendedVsync, not on-screen presentation time.
Only rows with Flags == 0 and valid completed-frame timestamps enter statistics.
Deadline rates have their own denominator: frames with a known FrameDeadline.
Raw dump text, CSV fields (as strings and integers), and exclusion reasons are
retained in JSON. Multiple inputs are reported independently, never pooled.
"""

from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import sys

LONG_MAX = (1 << 63) - 1
REQUIRED = {"flags", "intendedvsync", "framecompleted"}
SOURCES = {
    "dumpsys": "https://developer.android.com/tools/dumpsys#test_ui_performance",
    "field_definitions": "https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/FrameInfo.h",
    "jank_tracker": "https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/JankTracker.cpp",
    "frame_timeline": "https://perfetto.dev/docs/data-sources/frametimeline",
}


def key(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def percentile(values: list[int], fraction: float) -> float | None:
    """R-7 linear interpolation, as used by numpy.percentile's default method."""
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    low, high = math.floor(index), math.ceil(index)
    return ordered[low] + (ordered[high] - ordered[low]) * (index - low)


def distribution_ms(values: list[int]) -> dict:
    return {
        f"p{pct}_ms": None if not values else round(percentile(values, pct / 100) / 1e6, 6)
        for pct in (50, 90, 95, 99)
    } | {"max_ms": None if not values else round(max(values) / 1e6, 6)}


def timestamp(value: int | None) -> bool:
    return value is not None and 0 < value < LONG_MAX


def parse_row(headers: list[str], fields: list[str], line: int, raw: str) -> dict:
    record = {"line": line, "raw_csv": raw, "raw_values": dict(zip(headers, fields)), "excluded_reasons": []}
    if len(fields) != len(headers):
        record["excluded_reasons"].append("column_count_mismatch")
    numbers = {}
    for name, value in zip(headers, fields):
        try:
            numbers[name] = int(value, 10)
        except ValueError:
            record["excluded_reasons"].append(f"non_integer:{name}")
    record["metrics"] = numbers
    values = {key(name): value for name, value in numbers.items()}
    flags = values.get("flags")
    start, end = values.get("intendedvsync"), values.get("framecompleted")
    if flags is None:
        record["excluded_reasons"].append("missing_flags")
    elif flags != 0:
        record["excluded_reasons"].append("flags_nonzero")
    if not timestamp(start) or not timestamp(end):
        record["excluded_reasons"].append("invalid_or_unfinished_timestamp")
    elif end < start:
        record["excluded_reasons"].append("completed_before_intended_vsync")
    else:
        record["duration_ns"] = end - start
    record["valid"] = not record["excluded_reasons"]

    deadline = values.get("framedeadline")
    valid_deadline = timestamp(deadline) and timestamp(start) and deadline > start
    record["deadline_available"] = valid_deadline
    if valid_deadline and timestamp(end):
        record["completion_deadline_overrun_ns"] = end - deadline
        record["completion_missed_deadline"] = end > deadline
    gpu = values.get("gpucompleted")
    if valid_deadline and timestamp(gpu) and gpu >= start:
        record["gpu_deadline_overrun_ns"] = gpu - deadline
        # AOSP JankTracker's successful GPU comparison is strictly < deadline.
        record["gpu_missed_deadline"] = gpu >= deadline
    return record


def summarize(records: list[dict]) -> dict:
    valid = [row for row in records if row["valid"]]
    completion = [row for row in valid if "completion_missed_deadline" in row]
    gpu = [row for row in valid if "gpu_missed_deadline" in row]
    completion_misses = sum(row["completion_missed_deadline"] for row in completion)
    gpu_misses = sum(row["gpu_missed_deadline"] for row in gpu)
    return {
        "raw_frame_count": len(records),
        "valid_frame_count": len(valid),
        "excluded_frame_count": len(records) - len(valid),
        "exclusion_reason_counts": dict(Counter(reason for row in records for reason in row["excluded_reasons"])),
        "duration": distribution_ms([row["duration_ns"] for row in valid]),
        "completion_deadline": {
            "comparison": "FrameCompleted > FrameDeadline",
            "eligible_frame_count": len(completion),
            "missing_deadline_frame_count": len(valid) - len(completion),
            "missed_count": completion_misses,
            "missed_percent": round(100 * completion_misses / len(completion), 6) if completion else None,
        },
        "gpu_deadline": {
            "comparison": "GpuCompleted >= FrameDeadline",
            "eligible_frame_count": len(gpu),
            "missed_count": gpu_misses,
            "missed_percent": round(100 * gpu_misses / len(gpu), 6) if gpu else None,
        },
    }


def analyze(text: str, source: str) -> dict:
    blocks = []
    active = None
    previous_context = []
    for line_number, raw in enumerate(text.splitlines(), 1):
        stripped = raw.strip()
        if stripped == "---PROFILEDATA---":
            active = None
            continue
        fields = [field.strip() for field in next(csv.reader([stripped]))]
        # Android emits a trailing comma; retain empty internal fields as errors.
        if fields and fields[-1] == "":
            fields.pop()
        if REQUIRED.issubset({key(field) for field in fields}):
            active = {"block_index": len(blocks), "header_line": line_number,
                      "context": previous_context[-4:], "headers": fields, "frames": []}
            blocks.append(active)
        elif active is not None and fields and re.fullmatch(r"[-+]?\d+", fields[0]):
            active["frames"].append(parse_row(active["headers"], fields, line_number, raw))
        elif stripped:
            previous_context.append(stripped)
            if active is not None:
                active = None
    frames = [frame for block in blocks for frame in block["frames"]]
    for block in blocks:
        block["summary"] = summarize(block["frames"])
    warnings = []
    if not blocks:
        warnings.append("No recognized framestats CSV header; this may not be a framestats dump.")
    if len(blocks) > 1:
        warnings.append("Aggregate includes multiple profile blocks/windows. Inspect per-block summaries; these are not unique display frames.")
    if len(frames) >= 120:
        warnings.append("gfxinfo retains a finite recent-frame ring. This dump may omit the beginning of a long workload; compare per-block retained counts with the raw summary.")
    return {"source": source, "sha256": hashlib.sha256(text.encode()).hexdigest(),
            "summary": summarize(frames), "warnings": warnings, "blocks": blocks, "raw_dump": text}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+", type=Path, help="Saved gfxinfo framestats text files")
    parser.add_argument("--output", "-o", type=Path, help="Write complete JSON here; otherwise stdout")
    args = parser.parse_args()
    captures = [analyze(path.read_bytes().decode("utf-8"), str(path.resolve())) for path in args.inputs]
    report = {
        "schema_version": 1,
        "method": {
            "duration": "FrameCompleted - IntendedVsync; nanoseconds converted to milliseconds",
            "percentiles": "R-7 linear interpolation over valid completed frames",
            "inclusion": "Flags == 0, integer fields, 0 < IntendedVsync <= FrameCompleted < INT64_MAX",
            "deadline": "Unknown, zero, invalid and INT64_MAX deadlines are excluded from deadline denominators; no assumed refresh-rate fallback.",
            "interpretation": "App frame latency and deadline comparisons are not display-presented jank or FPS. Perfetto FrameTimeline attributes app versus compositor delays.",
            "raw_integer_precision": "raw_values retains exact decimal strings for consumers whose JSON numbers cannot represent int64 timestamps exactly.",
        },
        "sources": SOURCES,
        "captures": captures,
    }
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered)
        for capture in captures:
            print(json.dumps({"source": capture["source"], **capture["summary"]}), file=sys.stderr)
    else:
        sys.stdout.write(rendered)
    return 0 if all(capture["blocks"] for capture in captures) else 2


if __name__ == "__main__":
    raise SystemExit(main())
