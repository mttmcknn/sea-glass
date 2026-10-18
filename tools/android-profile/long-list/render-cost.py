#!/usr/bin/env python3
"""Attribute RenderThread scheduled CPU in saved long-list blocks (offline only).

Example:
  python3 render-cost.py /evidence/blocks/baseline /evidence/blocks/candidate \
    --trace-processor /tools/trace_processor_shell --output /evidence/render-cost

Default phases are down/revisit; use --phases down for one saved trace. This
requires full analyze.py validation of scenario identity, gestures and runtime
counters before interpreting comparisons. Inspect every result flag; flagged
captures return a nonzero exit status. No builds, device commands or network
calls are made here.

CPU is time intersecting sched running intervals, not GPU execution. Exclusive
columns subtract direct children and can be added; inclusive columns cannot.
Texture names/dimensions do not identify their Bitmap or material owner. A frame
without a UI upload marker can still contain framework uploads. Blur/shadow
cost cannot be inferred solely from generic draw/flush labels.
"""
import argparse
import bisect
import collections
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys


def query(pid):
    return f"""
SELECT s.id,s.ts,s.dur,s.name,s.parent_id,s.depth,s.track_id,
       t.utid,t.tid,t.name AS thread_name
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND (t.name='RenderThread' OR t.tid=p.pid)
ORDER BY s.ts,s.depth;

SELECT s.ts,s.dur,t.utid,t.name AS thread_name
FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND s.dur>=0 ORDER BY t.utid,s.ts;

SELECT f.ts,f.dur,f.surface_frame_token,f.jank_type,f.present_type,f.on_time_finish
FROM actual_frame_timeline_slice f JOIN process p USING(upid)
WHERE p.pid={pid} AND f.layer_name GLOB '*MainActivity*' ORDER BY f.ts;

SELECT name,idx,severity,value FROM stats
WHERE value>0 AND (severity!='info' OR name GLOB '*overrun_delta*');

SELECT (SELECT COUNT(*) FROM gpu_slice) AS gpu_slice_count,
       (SELECT COUNT(*) FROM gpu_counter_track) AS gpu_counter_track_count;
"""


def parse_tables(text):
    """Trace Processor's CSV output separates result tables by blank lines."""
    def convert(value):
        if value == '[NULL]':
            return None
        try:
            return int(value)
        except ValueError:
            try:
                return float(value)
            except ValueError:
                return value

    tables, rows, header = [], [], None
    for row in csv.reader(io.StringIO(text)):
        if not row:
            if header is not None:
                tables.append(rows)
            rows, header = [], None
        elif header is None:
            header = row
        else:
            if len(row) != len(header):
                raise ValueError('Malformed Trace Processor CSV row')
            rows.append(dict(zip(header, map(convert, row))))
    if header is not None:
        tables.append(rows)
    if len(tables) != 5:
        raise ValueError(f'Expected five query result tables, got {len(tables)}')
    return tables


def normalize(name):
    for prefix, category in (
        ('Texture upload(', 'Texture upload'), ('DrawFrames ', 'DrawFrames'),
        ('Drawing ', 'Drawing'), ('dequeueBuffer -', 'dequeueBuffer outer'),
        ('onFrameAvailable -', 'onFrameAvailable'),
        ('HWUI RAM cache:', 'HWUI RAM cache'),
        ('Choreographer#doFrame ', 'Choreographer#doFrame'),
    ):
        if name.startswith(prefix):
            return category
    for stage in ('onExecute', 'onPrepare'):
        if f'::{stage}(' in name:
            return f'OpsTask::{stage}'
    if name.startswith('SeaGlass.') and ':' in name:
        return name.split(':')[0]
    if name.startswith('Texture '):
        return re.sub(r'\d+', '#', name)
    return name


class ScheduledCpu:
    """Integral of sorted, disjoint running intervals, in integer nanoseconds."""
    def __init__(self, rows):
        self.starts = [row['ts'] for row in rows]
        self.ends = [row['ts'] + row['dur'] for row in rows]
        self.prefix = [0]
        for row in rows:
            self.prefix.append(self.prefix[-1] + row['dur'])
        self.overlaps = sum(self.ends[i] > self.starts[i + 1]
                            for i in range(len(rows) - 1))

    def integral(self, timestamp):
        index = bisect.bisect_right(self.starts, timestamp) - 1
        if index < 0:
            return 0
        return self.prefix[index] + min(timestamp - self.starts[index],
                                        self.ends[index] - self.starts[index])

    def during(self, start, end):
        return self.integral(end) - self.integral(start)


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    index = int(position)
    return values[index] + (values[min(index + 1, len(values) - 1)] - values[index]) * (position - index)


def frame_token(name, prefix):
    match = re.fullmatch(re.escape(prefix) + r' ([0-9]+)', name)
    return int(match.group(1)) if match else None


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def analyze(block, phase, trace_processor, output):
    label = block.name
    prefix = block / f'{label}-{phase}'
    required = [block / 'block.complete', block / 'process.pid']
    required += [Path(f'{prefix}.{suffix}') for suffix in
                 ('complete', 'pid-before', 'pid-after', 'perfetto-trace')]
    for path in required:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f'Missing or empty capture file: {path}')
    pid = int((block / 'process.pid').read_text().strip())
    if pid <= 0 or any(int(Path(f'{prefix}.{suffix}').read_text().strip()) != pid
                       for suffix in ('pid-before', 'pid-after')):
        raise ValueError(f'Invalid or changed capture PID: {prefix}')

    trace = Path(f'{prefix}.perfetto-trace')
    directory = output / f'{label}-{phase}'
    directory.mkdir(parents=True, exist_ok=True)
    sql = query(pid)
    (directory / 'query.sql').write_text(sql)
    # Always re-query: an existing output directory must not reuse stale CSV.
    process = subprocess.run([str(trace_processor), 'query', str(trace), sql],
                             capture_output=True, text=True)
    (directory / 'raw.csv').write_text(process.stdout)
    (directory / 'query.log').write_text(process.stderr)
    if process.returncode:
        raise ValueError(f'Trace Processor failed; inspect {directory / "query.log"}')
    slices, sched, timeline, health, gpu = parse_tables(process.stdout)

    by_thread = collections.defaultdict(list)
    for row in sched:
        by_thread[row['utid']].append(row)
    cpus = {utid: ScheduledCpu(rows) for utid, rows in by_thread.items()}
    by_id = {row['id']: row for row in slices}
    children = collections.defaultdict(list)
    for row in slices:
        if row['dur'] >= 0 and row['parent_id'] in by_id:
            children[row['parent_id']].append(row)

    categories = collections.defaultdict(lambda: dict(
        count=0, wall_ns=0, cpu_ns=0, exclusive_wall_ns=0, exclusive_cpu_ns=0,
        max_wall_ns=0, max_cpu_ns=0))
    dimensions = collections.defaultdict(lambda: dict(count=0, cpu_ns=0, wall_ns=0))
    upload_parents = collections.Counter()
    negative = []
    frame_of = {}
    rt_ids = {row['utid'] for row in slices + sched if row['thread_name'] == 'RenderThread'}
    for row in slices:
        if row['dur'] < 0:
            continue
        cpu = cpus.get(row['utid'])
        running = cpu.during(row['ts'], row['ts'] + row['dur']) if cpu else 0
        row['cpu_ns'] = running
        row['exclusive_wall_ns'] = row['dur'] - sum(child['dur'] for child in children[row['id']])
        child_cpu = sum(cpu.during(child['ts'], child['ts'] + child['dur'])
                        for child in children[row['id']]) if cpu else 0
        row['exclusive_cpu_ns'] = running - child_cpu
        if row['exclusive_cpu_ns'] < 0 or row['exclusive_wall_ns'] < 0:
            negative.append(row['id'])
        if row['thread_name'] != 'RenderThread':
            continue
        name = normalize(row['name'])
        item = categories[name]
        item['count'] += 1
        item['wall_ns'] += row['dur']
        item['cpu_ns'] += running
        item['exclusive_wall_ns'] += row['exclusive_wall_ns']
        item['exclusive_cpu_ns'] += row['exclusive_cpu_ns']
        item['max_wall_ns'] = max(item['max_wall_ns'], row['dur'])
        item['max_cpu_ns'] = max(item['max_cpu_ns'], running)
        parent = row
        while parent and frame_token(parent['name'], 'DrawFrames') is None:
            parent = by_id.get(parent['parent_id'])
        frame_of[row['id']] = parent['id'] if parent else None
        if name == 'Texture upload':
            item = dimensions[row['name'].split(') ')[-1]]
            item['count'] += 1
            item['cpu_ns'] += running
            item['wall_ns'] += row['dur']
            parent = by_id.get(row['parent_id'])
            upload_parents[normalize(parent['name']) if parent else 'none'] += 1

    by_frame = collections.defaultdict(list)
    for row in slices:
        if row['id'] in frame_of and row['dur'] >= 0:
            by_frame[frame_of[row['id']]].append(row)
    timeline_by_token = {row['surface_frame_token']: row for row in timeline}
    ui_slices = [row for row in slices if row['tid'] == pid and row['dur'] >= 0]
    ui_frames = {frame_token(row['name'], 'Choreographer#doFrame'): row for row in ui_slices
                 if frame_token(row['name'], 'Choreographer#doFrame') is not None}
    frames = []
    for frame in slices:
        token = frame_token(frame['name'], 'DrawFrames')
        if token is None or frame['thread_name'] != 'RenderThread' or frame['dur'] < 0:
            continue
        ui = ui_frames.get(token)
        members = by_frame[frame['id']]
        ui_members = [row for row in ui_slices if ui and row['ts'] >= ui['ts']
                      and row['ts'] + row['dur'] <= ui['ts'] + ui['dur']]
        costs = collections.defaultdict(float)
        for row in members:
            costs[normalize(row['name'])] += row['exclusive_cpu_ns'] / 1e6
        timeline_frame = timeline_by_token.get(token, {})
        frames.append(dict(
            id=frame['id'], token=token, ts=frame['ts'],
            rt_wall_ms=frame['dur'] / 1e6, rt_cpu_ms=frame['cpu_ns'] / 1e6,
            ui_wall_ms=ui['dur'] / 1e6 if ui else None,
            ui_cpu_ms=ui['cpu_ns'] / 1e6 if ui else None,
            jank_type=timeline_frame.get('jank_type'), present_type=timeline_frame.get('present_type'),
            upload_count=sum(normalize(row['name']) == 'Texture upload' for row in members),
            rt_texture_upload_cpu_ms=sum(row['cpu_ns'] / 1e6 for row in members
                                         if normalize(row['name']) == 'Texture upload'),
            ui_material_uploads=sum(row['name'] == 'SeaGlass.TextureUpload' for row in ui_members),
            ui_geometry_builds=sum(row['name'] == 'SeaGlass.Geometry' for row in ui_members),
            ui_postAndWait_wall_ms=sum(row['dur'] / 1e6 for row in ui_members if row['name'] == 'postAndWait'),
            ui_postAndWait_cpu_ms=sum(row['cpu_ns'] / 1e6 for row in ui_members if row['name'] == 'postAndWait'),
            exclusive_cpu_ms=dict(sorted(costs.items(), key=lambda item: -item[1])),
        ))

    app_cpu = sum(cpu.prefix[-1] for cpu in cpus.values())
    rt_cpu = sum(cpus[utid].prefix[-1] for utid in rt_ids if utid in cpus)
    exclusive = sum(item['exclusive_cpu_ns'] for item in categories.values())
    totals = dict(
        scheduled_app_cpu_ms=app_cpu / 1e6, rt_cpu_ms=rt_cpu / 1e6,
        rt_share_pct=100 * rt_cpu / app_cpu if app_cpu else None,
        rt_exclusive_cpu_ms=exclusive / 1e6, rt_untraced_cpu_ms=(rt_cpu - exclusive) / 1e6,
        overlapping_sched_intervals=sum(cpu.overlaps for cpu in cpus.values()),
        negative_exclusive_slice_ids=negative,
        incomplete_rt_slices=sum(row['dur'] < 0 and row['thread_name'] == 'RenderThread' for row in slices),
    )
    flags = []
    for key in ('overlapping_sched_intervals', 'negative_exclusive_slice_ids', 'incomplete_rt_slices'):
        if totals[key]:
            flags.append(key)
    if exclusive > rt_cpu:
        flags.append('exclusive_cpu_exceeds_scheduled_cpu')
    if any(row['severity'] == 'data_loss' or 'overrun_delta' in row['name'] for row in health):
        flags.append('trace_data_loss')
    if any(row['dur'] < 0 and frame_token(row['name'], 'Choreographer#doFrame') is not None
           for row in slices):
        flags.append('incomplete_ui_frame')
    if any(row['dur'] < 0 for row in timeline):
        flags.append('incomplete_frame_timeline')
    if phase in ('down', 'revisit'):
        if not frames or not app_cpu or not rt_cpu:
            flags.append('missing_scroll_frames_or_scheduler_data')
        if not any(row['name'] == 'SeaGlass.TextureUpload' for row in ui_slices):
            flags.append('missing_ui_texture_upload_instrumentation')

    for item in categories.values():
        for key in list(item):
            if key.endswith('_ns'):
                item[key[:-3] + '_ms'] = item.pop(key) / 1e6
    categories = dict(sorted(categories.items(), key=lambda item: -item[1]['exclusive_cpu_ms']))
    no_new = [frame for frame in frames if frame['ui_wall_ms'] is not None
              and frame['ui_material_uploads'] == 0]
    result = dict(
        label=label, phase=phase, pid=pid, trace=str(trace),
        trace_sha256=hashlib.sha256(trace.read_bytes()).hexdigest(), flags=flags,
        totals=totals, by_name=categories,
        texture_upload_dimensions=dict(sorted(dimensions.items(), key=lambda item: -item[1]['count'])),
        texture_upload_parents=dict(upload_parents), frame_count=len(frames),
        unmatched_ui_frame_count=sum(frame['ui_wall_ms'] is None for frame in frames),
        ui_upload_instrumentation_seen=any(row['name'] == 'SeaGlass.TextureUpload' for row in ui_slices),
        frames_without_new_material_upload=dict(
            count=len(no_new), texture_upload_count=sum(frame['upload_count'] for frame in no_new),
            texture_upload_cpu_ms=sum(frame['rt_texture_upload_cpu_ms'] for frame in no_new),
            median_texture_uploads=percentile([frame['upload_count'] for frame in no_new], .5)),
        frame_stats=dict(
            ui_p95_ms=percentile([frame['ui_wall_ms'] for frame in frames if frame['ui_wall_ms'] is not None], .95),
            rt_cpu_p95_ms=percentile([frame['rt_cpu_ms'] for frame in frames], .95),
            rt_wall_p95_ms=percentile([frame['rt_wall_ms'] for frame in frames], .95)),
        worst_ui_frames=sorted(frames, key=lambda frame: frame['ui_wall_ms'] or 0, reverse=True)[:8],
        worst_rt_frames=sorted(frames, key=lambda frame: frame['rt_cpu_ms'], reverse=True)[:8],
        health=health, gpu=gpu,
    )
    write_json(directory / 'summary.json', result)
    write_json(directory / 'frames.json', frames)
    fields = ['name', 'count', 'wall_ms', 'cpu_ms', 'exclusive_wall_ms',
              'exclusive_cpu_ms', 'max_wall_ms', 'max_cpu_ms']
    with (directory / 'categories.csv').open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(dict(name=name, **item) for name, item in categories.items())
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('blocks', type=Path, nargs='+', help='Directories pulled by capture-block.sh')
    parser.add_argument('--trace-processor', type=Path, required=True, help='Local trace_processor_shell executable')
    parser.add_argument('--output', type=Path, required=True, help='Analysis directory (may be large; raw CSV is retained)')
    parser.add_argument('--phases', nargs='+', choices=('cold', 'down', 'revisit', 'idle'), default=['down', 'revisit'])
    args = parser.parse_args()
    blocks = [block.resolve() for block in args.blocks]
    if len({block.name for block in blocks}) != len(blocks):
        parser.error('Block directory names must be unique')
    if len(set(args.phases)) != len(args.phases):
        parser.error('Phases must be unique')
    args.output = args.output.resolve()
    if any(args.output == block or args.output.is_relative_to(block) for block in blocks):
        parser.error('Output must be outside input capture directories')
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        for block in blocks:
            for phase in args.phases:
                result = analyze(block, phase, args.trace_processor.resolve(), args.output)
                results.append(result)
                print(f'{block.name}/{phase}: RT CPU {result["totals"]["rt_cpu_ms"]:.3f} ms; '
                      f'flags={result["flags"]}', flush=True)
        write_json(args.output / 'summary.json', results)
    except (OSError, ValueError) as error:
        print(f'Analysis failed: {error}', file=sys.stderr)
        return 1
    return int(any(result['flags'] for result in results))


if __name__ == '__main__':
    raise SystemExit(main())
