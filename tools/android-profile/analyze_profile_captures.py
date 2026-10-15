#!/usr/bin/env python3
"""Host-only analysis of saved SeaGlass Perfetto/gfxinfo capture pairs."""
import argparse
import csv
import io
import json
from pathlib import Path
import subprocess

from analyze_framestats import analyze, percentile

ROOT = Path(__file__).resolve().parent
TP = ROOT / "profiling-tools/trace_processor_shell"
SQL = """
SELECT name, COUNT(*) AS slice_count,
       SUM(CASE WHEN dur < 0 THEN 1 ELSE 0 END) AS incomplete_count,
       SUM(CASE WHEN dur >= 0 THEN dur ELSE 0 END) / 1e6 AS total_ms,
       PERCENTILE(CASE WHEN dur >= 0 THEN dur / 1e6 ELSE NULL END, 50) AS p50_ms,
       PERCENTILE(CASE WHEN dur >= 0 THEN dur / 1e6 ELSE NULL END, 95) AS p95_ms,
       MAX(CASE WHEN dur >= 0 THEN dur ELSE NULL END) / 1e6 AS max_ms
FROM slice WHERE name GLOB 'SeaGlass.*' GROUP BY name;

SELECT COUNT(*) AS frame_count,
       SUM(CASE WHEN dur < 0 THEN 1 ELSE 0 END) AS incomplete_count,
       SUM(CASE WHEN dur >= 0 AND jank_type GLOB '*App Deadline Missed*' THEN 1 ELSE 0 END) AS app_deadline_missed,
       SUM(CASE WHEN dur >= 0 AND jank_type GLOB '*Buffer Stuffing*' THEN 1 ELSE 0 END) AS buffer_stuffed,
       SUM(CASE WHEN dur >= 0 AND present_type = 'Dropped Frame' THEN 1 ELSE 0 END) AS dropped,
       SUM(CASE WHEN dur >= 0 AND on_time_finish = 1 THEN 1 ELSE 0 END) AS on_time_finish,
       PERCENTILE(CASE WHEN dur >= 0 THEN dur / 1e6 ELSE NULL END, 50) AS p50_ms,
       PERCENTILE(CASE WHEN dur >= 0 THEN dur / 1e6 ELSE NULL END, 95) AS p95_ms,
       MAX(CASE WHEN dur >= 0 THEN dur ELSE NULL END) / 1e6 AS max_ms
FROM actual_frame_timeline_slice f JOIN process p USING (upid)
WHERE p.name = 'dev.mttmcknn.seaglass.sample';

SELECT layer_name, jank_type, on_time_finish, present_type, COUNT(*) AS frames
FROM actual_frame_timeline_slice f JOIN process p USING (upid)
WHERE p.name = 'dev.mttmcknn.seaglass.sample' AND dur >= 0
GROUP BY layer_name, jank_type, on_time_finish, present_type;

SELECT name, idx, severity, value FROM stats
WHERE value > 0 AND (severity != 'info' OR name GLOB '*overrun_delta*');

WITH events AS (
  SELECT (SELECT a.id FROM ancestor_slice(s.id) a
          WHERE a.name GLOB 'Choreographer#doFrame*' LIMIT 1) AS frame_id,
         s.name, s.dur
  FROM slice s WHERE s.name GLOB 'SeaGlass.*' AND s.dur >= 0
), frames AS (
  SELECT frame_id,
         SUM(CASE WHEN name='SeaGlass.Geometry' THEN 1 ELSE 0 END) AS geometry_count,
         SUM(CASE WHEN name='SeaGlass.Geometry' THEN dur ELSE 0 END) / 1e6 AS geometry_ms,
         SUM(CASE WHEN name='SeaGlass.TextureUpload' THEN dur ELSE 0 END) / 1e6 AS upload_ms,
         SUM(dur) / 1e6 AS material_ms
  FROM events WHERE frame_id IS NOT NULL GROUP BY frame_id
)
SELECT COUNT(*) AS instrumented_frame_count,
       SUM(CASE WHEN geometry_count > 0 THEN 1 ELSE 0 END) AS geometry_frame_count,
       MIN(geometry_count) AS min_geometry_surfaces,
       MAX(geometry_count) AS max_geometry_surfaces,
       PERCENTILE(geometry_ms,50) AS geometry_p50_ms,
       PERCENTILE(geometry_ms,95) AS geometry_p95_ms,
       MAX(geometry_ms) AS geometry_max_ms,
       PERCENTILE(upload_ms,95) AS upload_p95_ms,
       PERCENTILE(material_ms,95) AS material_p95_ms
FROM frames;

SELECT s.name, COUNT(*) AS count, SUM(s.dur)/1e6 AS total_ms,
       PERCENTILE(s.dur/1e6,95) AS p95_ms, MAX(s.dur)/1e6 AS max_ms
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='dev.mttmcknn.seaglass.sample' AND s.dur>=0
  AND s.name IN ('shader_compile','flush commands','postAndWait')
GROUP BY s.name;

WITH material AS (
  SELECT (SELECT a.id FROM ancestor_slice(s.id) a
          WHERE a.name GLOB 'Choreographer#doFrame*' LIMIT 1) AS frame_id,
         s.name,s.dur
  FROM slice s WHERE s.name GLOB 'SeaGlass.*' AND s.dur>=0
), costs AS (
  SELECT frame_id,
         SUM(CASE WHEN name='SeaGlass.Geometry' THEN 1 ELSE 0 END) AS geometry_count,
         SUM(CASE WHEN name='SeaGlass.TextureUpload' THEN 1 ELSE 0 END) AS upload_count,
         SUM(CASE WHEN name='SeaGlass.ProgramCreate' THEN 1 ELSE 0 END) AS program_count,
         SUM(CASE WHEN name='SeaGlass.Geometry' THEN dur ELSE 0 END)/1e6 AS geometry_ms,
         SUM(CASE WHEN name='SeaGlass.ProgramCreate' THEN dur ELSE 0 END)/1e6 AS program_ms,
         SUM(CASE WHEN name='SeaGlass.TextureUpload' THEN dur ELSE 0 END)/1e6 AS upload_ms
  FROM material GROUP BY frame_id
)
SELECT d.id AS frame_id,d.ts AS frame_start_ns,d.name,d.dur/1e6 AS do_frame_ms,
       c.geometry_count,c.upload_count,c.program_count,c.geometry_ms,c.program_ms,c.upload_ms,
       f.dur/1e6 AS timeline_ms,f.jank_type,f.on_time_finish,f.present_type
FROM costs c JOIN slice d ON d.id=c.frame_id
LEFT JOIN actual_frame_timeline_slice f
  ON f.surface_frame_token=CAST(substr(d.name,instr(d.name,' ')+1) AS INT)
 AND f.layer_name GLOB '*MainActivity*'
WHERE c.geometry_count>0 OR c.upload_count>0 ORDER BY d.ts;
"""


def convert(value):
    if value == "[NULL]":
        return None
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value


def parse_tables(text):
    tables, table, header = [], [], None
    for row in csv.reader(io.StringIO(text)):
        if not row:
            if header is not None:
                tables.append(table)
            table, header = [], None
        elif header is None:
            header = row
        else:
            table.append({name: convert(value) for name, value in zip(header, row)})
    if header is not None:
        tables.append(table)
    return tables


def summarize_changed_frames(frames):
    # Texture upload marks a geometry-key change even when a shared cache avoids
    # every Geometry slice. Count work once if a frame has multiple timeline rows.
    unique = list({frame["frame_id"]: frame for frame in frames}.values())
    summary = {"frame_count": len(unique),
               "geometry_build_count": sum(frame["geometry_count"] for frame in unique),
               "texture_upload_count": sum(frame["upload_count"] for frame in unique),
               "program_create_count": sum(frame["program_count"] for frame in unique),
               "zero_geometry_frame_count": sum(frame["geometry_count"] == 0 for frame in unique)}
    for label, field in (("geometry", "geometry_ms"), ("upload", "upload_ms"),
                         ("program", "program_ms"), ("ui_do_frame", "do_frame_ms")):
        values = [frame[field] for frame in unique]
        summary[label + "_p50_ms"] = percentile(values, .5)
        summary[label + "_p95_ms"] = percentile(values, .95)
        summary[label + "_max_ms"] = max(values) if values else None
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path, default=ROOT / "emulator-profile")
    parser.add_argument("--pattern", default="*.perfetto-trace")
    parser.add_argument("--name", default="profile-analysis")
    parser.add_argument("--trace-processor", type=Path, default=TP,
                        help="Path to a Perfetto trace_processor executable")
    args = parser.parse_args()
    captures = []
    for trace in sorted(args.directory.glob(args.pattern)):
        stem = trace.name.removesuffix(".perfetto-trace")
        gfx = trace.with_name(stem + ".gfxinfo")
        if not gfx.exists():
            continue
        result = subprocess.run([str(args.trace_processor), "query", str(trace), SQL], capture_output=True, text=True, check=True)
        tables = parse_tables(result.stdout)
        if len(tables) != 7:
            raise RuntimeError(f"{stem}: expected seven SQL tables, got {len(tables)}")
        stages = {row["name"]: row for row in tables[0]}
        for name in ("Geometry", "TextureUpload", "ProgramCreate", "Ground", "Draw"):
            stage = f"SeaGlass.{name}"
            stages.setdefault(stage, {"name": stage, "slice_count": 0, "incomplete_count": 0,
                                      "total_ms": 0, "p50_ms": None, "p95_ms": None, "max_ms": None})
        timeline = tables[1][0]
        completed = timeline["frame_count"] - (timeline["incomplete_count"] or 0)
        timeline["app_deadline_missed_percent"] = 100 * timeline["app_deadline_missed"] / completed if completed else None
        frame_report = analyze(gfx.read_bytes().decode(), str(gfx.resolve()))
        stage_frames = [frame for frame in tables[6] if frame["upload_count"] > 0]
        geometry_frames = [frame for frame in tables[6] if frame["geometry_count"] > 0]
        capture = {"name": stem, "stages": stages, "frame_timeline": timeline,
                   "frame_timeline_classifications": tables[2], "trace_health": tables[3],
                   "per_do_frame": tables[4][0], "gfxinfo": frame_report["summary"],
                   "native_pipeline": tables[5], "geometry_frames": geometry_frames,
                   "stage_frames": stage_frames, "per_changed_frame": summarize_changed_frames(stage_frames),
                   "recommended_frame_metric": "FrameTimeline and scene-change stage_frames; gfxinfo ring is partial" if "switch" in stem else "All valid gfxinfo frames for the slider workload",
                   "gfxinfo_warnings": frame_report["warnings"]}
        captures.append(capture)
        trace.with_name(stem + ".trace-analysis.log").write_text(result.stderr)
    report = {"metric_notes": [
        "SeaGlass stage slices are wall-clock durations, including any descheduling.",
        "FrameTimeline and gfxinfo have different start points and capture intervals; do not equate their durations or frame counts.",
        "FrameTimeline App Deadline Missed and gfxinfo completion/deadline comparisons are separate metrics.",
        "Ftrace data loss compromises exact slice totals/nesting even if no incomplete slices remain.",
        "stage_frames groups TextureUpload by ancestor doFrame, retaining cache-hit frames with zero Geometry. geometry_frames retains its original Geometry-present filter.",
        "Use all valid gfxinfo frames for slider latency. For scene switches use FrameTimeline and stage_frames because the gfxinfo ring truncates earlier frames.",
        "No visible frames or named slices must not be interpreted as proof of fast rendering."
    ], "captures": captures}
    (args.directory / (args.name + ".json")).write_text(json.dumps(report, indent=2) + "\n")
    columns = ["capture", "geometry_count", "geometry_total_ms", "geometry_p95_ms", "geometry_max_ms",
               "upload_total_ms", "upload_p95_ms", "program_create_count", "trace_frames",
               "trace_app_deadline_missed_percent", "trace_buffer_stuffed", "gfx_frames", "gfx_p50_ms",
               "gfx_p95_ms", "gfx_max_ms", "gfx_deadline_missed_percent", "data_loss",
               "changed_frames", "changed_geometry_p50_ms", "changed_upload_p50_ms", "changed_ui_do_frame_p50_ms"]
    with (args.directory / (args.name + ".csv")).open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(columns)
        for c in captures:
            g, u, p = (c["stages"][f"SeaGlass.{name}"] for name in ("Geometry", "TextureUpload", "ProgramCreate"))
            t, x = c["frame_timeline"], c["gfxinfo"]
            writer.writerow([c["name"], g["slice_count"], g["total_ms"], g["p95_ms"], g["max_ms"],
                             u["total_ms"], u["p95_ms"], p["slice_count"], t["frame_count"],
                             t["app_deadline_missed_percent"], t["buffer_stuffed"], x["valid_frame_count"],
                             x["duration"]["p50_ms"], x["duration"]["p95_ms"], x["duration"]["max_ms"],
                             x["completion_deadline"]["missed_percent"],
                             any(s["severity"] == "data_loss" for s in c["trace_health"]),
                             c["per_changed_frame"]["frame_count"], c["per_changed_frame"]["geometry_p50_ms"],
                             c["per_changed_frame"]["upload_p50_ms"], c["per_changed_frame"]["ui_do_frame_p50_ms"]])
    print(f"Analyzed {len(captures)} capture pairs into {args.directory / args.name}.json/.csv")


if __name__ == "__main__":
    main()
