#!/usr/bin/env python3
"""Validate and summarize two completed desktop first-paint logs; never run benchmarks.

Expected inputs: DIRECTORY/BEFORE/first-paint.log and DIRECTORY/AFTER/first-paint.log.
Run only after timing has stopped: reference validation reads and hashes 21 RGBA files.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics

FIXTURES = ("capsule", "disc", "toolbar", "music", "phone", "email", "chat")
ROUNDS = (1, 2, 3)
CACHES = ("cold", "revisit")
HEADER = "fixture,round,cache,first_frame_ms,first_frame_exact,first_frame_affected_region_channel_mae,first_frame_affected_region_changed_pixels_pct,final_frame_ms"
NUMBERS = ("first_frame_ms", "first_frame_affected_region_channel_mae", "first_frame_affected_region_changed_pixels_pct", "final_frame_ms")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            result.update(chunk)
    return result.hexdigest()


def stats(values):
    values = sorted(values)
    return {"n": len(values), "median": statistics.median(values),
            "p95NearestRank": values[math.ceil(len(values) * .95) - 1],
            "min": values[0], "max": values[-1], "values": values}


def change(before, after):
    return (after / before - 1) * 100 if before else None


def parse_log(path):
    text = path.read_text()
    require("BUILD SUCCESSFUL" in text and "BUILD FAILED" not in text, f"Incomplete/failed build: {path}")
    require(text.splitlines().count(HEADER) == 1, f"Expected one CSV header: {path}")
    lines = [line for line in text.splitlines() if line.split(",")[0] in FIXTURES]
    rows = []
    for raw in csv.DictReader([HEADER] + lines):
        require(None not in raw and all(v is not None for v in raw.values()), f"Malformed row: {raw}")
        row = dict(raw)
        row["round"] = int(row["round"])
        require(row["first_frame_exact"] in ("true", "false"), f"Invalid exact flag: {raw}")
        row["first_frame_exact"] = row["first_frame_exact"] == "true"
        for key in NUMBERS:
            row[key] = float(row[key])
            require(math.isfinite(row[key]) and row[key] >= 0, f"Invalid {key}: {raw}")
        require(row["final_frame_ms"] >= row["first_frame_ms"], f"Final before first observation: {raw}")
        require(row["first_frame_affected_region_channel_mae"] <= 255, f"Invalid channel MAE: {raw}")
        require(row["first_frame_affected_region_changed_pixels_pct"] <= 100, f"Invalid changed percentage: {raw}")
        if row["first_frame_exact"]:
            require(row["first_frame_affected_region_channel_mae"] == row["first_frame_affected_region_changed_pixels_pct"] == 0,
                    f"Exact first frame has nonzero error: {raw}")
        rows.append(row)
    expected = {(f, r, c) for f in FIXTURES for r in ROUNDS for c in CACHES}
    require(len(rows) == 42 and {(r["fixture"], r["round"], r["cache"]) for r in rows} == expected,
            f"Expected exactly 42 unique fixture/round/cache rows: {path}")
    return rows


def csv_file(path, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--before", default="baseline")
    parser.add_argument("--after", default="performance")
    parser.add_argument("--reference-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="Default: DIRECTORY/first-paint-analysis")
    parser.add_argument("--reference-manifest", type=Path,
                        help="Optional prior list of {path,bytes,sha256} or JSON containing references; hashes must match")
    args = parser.parse_args()
    require(args.before != args.after, "Before and after labels must differ")
    variants = (args.before, args.after)
    by_variant, inputs = {}, []
    for variant in variants:
        path = args.directory / variant / "first-paint.log"
        by_variant[variant] = parse_log(path)
        inputs.append({"variant": variant, "path": str(path), "sha256": digest(path), "buildSuccessful": True})
    references = sorted(args.reference_dir.glob("*.rgba"))
    expected_names = {f"{fixture}-{round_ - 1}.rgba" for fixture in FIXTURES for round_ in ROUNDS}
    require({p.name for p in references} == expected_names, "Expected exactly 21 named fixture/round RGBA references")
    require(all(p.stat().st_size == 520 * 1000 * 4 for p in references), "Wrong reference dimensions")
    reference_rows = [{"path": str(p), "bytes": p.stat().st_size, "sha256": digest(p)} for p in references]
    if args.reference_manifest:
        expected = json.loads(args.reference_manifest.read_text())
        if isinstance(expected, dict):
            expected = expected.get("references", expected.get("firstPaint", {}).get("references"))
        require(isinstance(expected, list), "Reference manifest must supply references list")
        actual_hashes = {Path(x["path"]).name: (x["bytes"], x["sha256"]) for x in reference_rows}
        expected_hashes = {Path(x["path"]).name: (x["bytes"], x["sha256"]) for x in expected}
        require(actual_hashes == expected_hashes, "Reference hashes changed from frozen manifest")
    raw_rows = [{"variant": variant, **row, "final_exact_by_harness_contract": True}
                for variant in variants for row in by_variant[variant]]
    summary_rows, cases, totals = [], [], {}
    for fixture in FIXTURES:
        for cache in CACHES:
            flat = {"fixture": fixture, "cache": cache, "samples_per_variant": 3}
            case = {"fixture": fixture, "cache": cache}
            for variant in variants:
                selected = [r for r in by_variant[variant] if r["fixture"] == fixture and r["cache"] == cache]
                entry = {"firstFrameMs": stats([r["first_frame_ms"] for r in selected]),
                         "finalFrameMs": stats([r["final_frame_ms"] for r in selected]),
                         "immediateExact": sum(r["first_frame_exact"] for r in selected),
                         "finalExactByHarnessContract": len(selected)}
                case[variant] = entry
                flat[f"{variant}_immediate_exact"] = entry["immediateExact"]
                flat[f"{variant}_final_exact_by_harness_contract"] = len(selected)
                flat[f"{variant}_median_first_frame_ms"] = entry["firstFrameMs"]["median"]
                flat[f"{variant}_median_final_frame_ms"] = entry["finalFrameMs"]["median"]
            flat["final_frame_median_change_pct"] = change(case[args.before]["finalFrameMs"]["median"], case[args.after]["finalFrameMs"]["median"])
            summary_rows.append(flat)
            cases.append(case)
    for variant in variants:
        rows = by_variant[variant]
        totals[variant] = {"samples": len(rows), "immediateExact": sum(r["first_frame_exact"] for r in rows),
                           "finalExactByHarnessContract": len(rows), "byCache": {}}
        for cache in CACHES:
            selected = [r for r in rows if r["cache"] == cache]
            totals[variant]["byCache"][cache] = {"samples": len(selected),
                "medianFinalFrameMs": statistics.median(r["final_frame_ms"] for r in selected),
                "immediateExact": sum(r["first_frame_exact"] for r in selected)}
    result = {"schemaVersion": 1, "before": args.before, "after": args.after,
              "inputs": inputs, "references": reference_rows, "priorReferenceManifest": str(args.reference_manifest) if args.reference_manifest else None,
              "totals": totals, "cases": cases,
              "finalExactEvidence": "BenchmarkFirstPaint.measureFirstPaint prints a row only after contentEquals(target); complete 42-row logs and BUILD SUCCESSFUL establish final equality under that inspected harness contract. This parser does not independently rerender pixels.",
              "limitations": ["First render observation may precede asynchronous insertion and be empty; first_frame_ms alone is not time to correct glass. Immediate exactness is reported separately.",
                "Three repetitions per fixture/cache in one JVM process per variant; descriptive medians, no significance claim.",
                "520x1000 desktop software composition/drawing/pixel-readback proxy with 4ms polling; not Android/iOS, GPU execution, or screen presentation.",
                "Reference hashes are recorded at analysis time. Only --reference-manifest proves equality to an earlier frozen manifest; logs do not themselves record the reference path."]}
    output = args.output or args.directory / "first-paint-analysis"
    output.mkdir(parents=True, exist_ok=True)
    (output / "first-paint-comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    csv_file(output / "first-paint-raw.csv", raw_rows)
    csv_file(output / "first-paint-summary.csv", summary_rows)
    (output / "reference-manifest.json").write_text(json.dumps(reference_rows, indent=2) + "\n")
    print(f"Validated {len(raw_rows)} measurements and {len(reference_rows)} references; outputs: {output}")
    for variant in variants:
        print(variant, json.dumps(totals[variant], sort_keys=True))


if __name__ == "__main__":
    main()
