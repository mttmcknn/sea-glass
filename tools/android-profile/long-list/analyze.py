#!/usr/bin/env python3
"""Analyze saved long-list blocks only. No device/build calls; writes explicit output."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def runtime(path):
    text = path.read_text()
    match = re.search(r'SEAGLASS_RUNTIME_STATS (\{.*\})', text)
    if not match:
        raise ValueError(f'Missing runtime JSON: {path}')
    return json.loads(match.group(1))


def number(value):
    if value is None:
        return None
    try:
        return float(value) if '.' in str(value) else int(value)
    except ValueError:
        return None


def runtime_delta(before, after):
    fields = ['processCpuTimeMs', 'uptimeMs']
    result = {key: after[key] - before[key] for key in fields}
    result['art'] = {}
    for key in ('art.gc.bytes-allocated', 'art.gc.bytes-freed', 'art.gc.gc-count',
                'art.gc.gc-time', 'art.gc.blocking-gc-count', 'art.gc.blocking-gc-time'):
        a, b = number(before['runtimeStats'].get(key)), number(after['runtimeStats'].get(key))
        result['art'][key] = b - a if a is not None and b is not None else None
    result['observerNote'] = 'Includes bracketing dump and trace orchestration costs; idle is reported separately.'
    return result


def position(scene):
    return (scene.get('firstVisibleItemIndex'), scene.get('firstVisibleItemScrollOffset'))


def memory(path):
    if not path.exists():
        return None
    text = path.read_text()
    result = {'path': str(path)}
    for label, pattern in {
        'pssKiB': r'TOTAL PSS:\s+(\d+)', 'rssKiB': r'TOTAL RSS:\s+(\d+)',
        'bitmapMallocCount': r'Bitmap \(malloced\):\s+(\d+)',
        'bitmapMallocKiB': r'Bitmap \(malloced\):\s+\d+\s+(\d+)'
    }.items():
        match = re.search(pattern, text)
        result[label] = int(match.group(1)) if match else None
    return result


def query(pid):
    return f"""
SELECT name,idx,severity,value FROM stats WHERE value>0 AND (severity!='info' OR name GLOB '*overrun_delta*');

SELECT s.name,COUNT(*) AS count,SUM(CASE WHEN s.dur<0 THEN 1 ELSE 0 END) AS incomplete,
 SUM(CASE WHEN s.dur>=0 THEN s.dur ELSE 0 END)/1e6 AS wall_ms,
 MAX(CASE WHEN s.dur>=0 THEN s.dur END)/1e6 AS max_ms
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND (s.name GLOB 'SeaGlass.*' OR s.name GLOB '* GC' OR
 s.name IN ('shader_compile','driver_compile_shader','driver_link_program','CompactionPause','ThreadFlipSuspendAll'))
GROUP BY s.name;

WITH app_timeline AS (
 SELECT f.surface_frame_token,MAX(f.ts+f.dur) AS app_complete_ns,
 MAX(CASE WHEN sf.dur>=0 AND f.present_type!='Dropped Frame' THEN sf.ts+sf.dur END) AS display_present_ns
 FROM actual_frame_timeline_slice f JOIN process p USING(upid)
 LEFT JOIN actual_frame_timeline_slice sf ON sf.display_frame_token=f.display_frame_token
 AND (sf.surface_frame_token=0 OR sf.surface_frame_token IS NULL) AND sf.layer_name IS NULL
 WHERE p.pid={pid} AND f.dur>=0 AND f.layer_name GLOB '*MainActivity*'
 GROUP BY f.surface_frame_token
)
SELECT d.id AS frame_id,d.ts,d.dur,a.app_complete_ns,a.display_present_ns,
 (SELECT COUNT(*) FROM descendant_slice(d.id) s WHERE s.name='SeaGlass.TextureUpload') AS uploads,
 (SELECT COUNT(*) FROM descendant_slice(d.id) s WHERE s.name GLOB 'SeaGlass.*') AS instrumented
FROM slice d JOIN thread_track tt ON tt.id=d.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
LEFT JOIN app_timeline a ON a.surface_frame_token=CAST(substr(d.name,instr(d.name,' ')+1) AS INT)
WHERE p.pid={pid} AND t.tid=p.pid AND d.name GLOB 'Choreographer#doFrame [0-9]*' ORDER BY d.ts;

SELECT f.ts,f.dur,f.surface_frame_token,f.jank_type,f.present_type,f.on_time_finish
FROM actual_frame_timeline_slice f JOIN process p USING(upid)
WHERE p.pid={pid} AND f.layer_name GLOB '*MainActivity*' ORDER BY f.ts;

SELECT t.tid,t.name AS thread_name,SUM(s.dur)/1e6 AS cpu_ms
FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND s.dur>=0 GROUP BY t.utid ORDER BY cpu_ms DESC;

SELECT s.name,COUNT(DISTINCT s.id) AS count,
 SUM(MIN(s.ts+s.dur,sc.ts+sc.dur)-MAX(s.ts,sc.ts))/1e6 AS cpu_ms
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
JOIN sched sc ON sc.utid=t.utid AND sc.ts<s.ts+s.dur AND sc.ts+sc.dur>s.ts AND sc.dur>=0
WHERE p.pid={pid} AND s.dur>=0 AND s.name IN ('SeaGlass.Geometry','SeaGlass.TextureUpload','SeaGlass.ProgramCreate','shader_compile')
GROUP BY s.name;

SELECT ct.name,ct.unit,MIN(c.value) AS min_value,MAX(c.value) AS max_value,
 (SELECT c2.value FROM counter c2 WHERE c2.track_id=ct.id ORDER BY c2.ts DESC LIMIT 1) AS last_value
FROM counter c JOIN process_counter_track ct ON ct.id=c.track_id JOIN process p USING(upid)
WHERE p.pid={pid} AND (lower(ct.name) GLOB '*heap*' OR lower(ct.name) GLOB '*memory*' OR ct.name GLOB 'SeaGlass.*') GROUP BY ct.id;

SELECT s.ts,s.dur,s.name FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND t.tid=p.pid AND s.name GLOB 'dispatchInputEvent MotionEvent ACTION_*' ORDER BY s.ts;

SELECT * FROM trace_bounds;

SELECT s.ts,s.name,(SELECT a.id FROM ancestor_slice(s.id) a WHERE a.name GLOB 'Choreographer#doFrame [0-9]*' LIMIT 1) AS frame_id
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.pid={pid} AND (s.name GLOB 'SeaGlass.Request:*' OR s.name GLOB 'SeaGlass.Applied:*') ORDER BY s.ts;

SELECT (SELECT COUNT(*) FROM gpu_slice) AS gpu_slice_count,(SELECT COUNT(*) FROM gpu_counter_track) AS gpu_counter_track_count;
"""


def distribution(values, percentile):
    return {'count': len(values), 'p50Ms': percentile(values, .5), 'p95Ms': percentile(values, .95),
            'maxMs': max(values) if values else None, 'sumMs': sum(values)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('blocks', type=Path, nargs='+')
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--trace-processor', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.repo / 'tools/android-profile'))
    from analyze_profile_captures import parse_tables
    from analyze_framestats import analyze as analyze_gfx, percentile
    manifest = json.loads(args.manifest.read_text())
    config = manifest['config']
    captures, block_results = [], []
    for block in args.blocks:
        label = block.name
        issues = []
        for suffix in ('started', 'complete', 'started-uptime', 'complete-uptime'):
            if not (block / f'block.{suffix}').is_file():
                raise ValueError(f'Incomplete block {block}: block.{suffix} missing')
        elapsed = float((block / 'block.complete-uptime').read_text()) - float((block / 'block.started-uptime').read_text())
        if elapsed > 45:
            issues.append(f'Block exceeds nominal 45 s budget: {elapsed:.2f}s; retain and explain.')
        pid = int((block / 'process.pid').read_text().strip())
        if hashlib.sha256((block / 'device-config.sh').read_bytes()).hexdigest() != manifest['deviceConfigSha256']:
            raise ValueError(f'Frozen device configuration mismatch: {block}')
        sizes = re.findall(r'(\d+)x(\d+)', (block / 'device-size.txt').read_text())
        densities = re.findall(r'density:\s*(\d+)', (block / 'device-density.txt').read_text())
        if not sizes or tuple(map(int, sizes[-1])) != (config['viewport_width'], config['viewport_height']):
            raise ValueError(f'Viewport mismatch: {block}')
        if not densities or int(densities[-1]) != config['density_dpi']:
            raise ValueError(f'Density mismatch: {block}')
        for phase in ('cold', 'down', 'revisit', 'idle'):
            prefix = block / f'{label}-{phase}'
            for suffix in ('complete', 'pid-before', 'pid-after', 'after', 'gfxinfo', 'perfetto-trace'):
                path = Path(f'{prefix}.{suffix}')
                if not path.is_file() or not path.stat().st_size:
                    raise ValueError(f'Missing/empty {path}')
            if any(int(Path(f'{prefix}.{suffix}').read_text().strip()) != pid for suffix in ('pid-before', 'pid-after')):
                raise ValueError(f'PID changed: {prefix}')
            before = runtime(Path(f'{prefix}.before')) if phase != 'cold' else None
            after = runtime(Path(f'{prefix}.after'))
            for snapshot in [x for x in (before, after) if x]:
                if snapshot['pid'] != pid or snapshot['allocationCountingRequested']:
                    raise ValueError(f'PID/counting mismatch: {prefix}')
                if snapshot.get('scrollScene', {}).get('datasetId') != config['dataset_id']:
                    raise ValueError(f'Dataset mismatch: {prefix}')
            flags = []
            scene_after = after['scrollScene']
            if scene_after.get('isScrollInProgress'):
                flags.append('phase_endpoint_still_scrolling')
            if before:
                scene_before = before['scrollScene']
                if None in (*position(scene_before), *position(scene_after)):
                    flags.append('missing_scroll_position_fields')
                elif phase == 'down' and not position(scene_after) > position(scene_before):
                    flags.append('down_did_not_advance')
                elif phase == 'revisit' and not position(scene_after) < position(scene_before):
                    flags.append('revisit_did_not_reverse')
                elif phase == 'idle' and position(scene_after) != position(scene_before):
                    flags.append('idle_position_changed')
            trace = Path(f'{prefix}.perfetto-trace')
            proc = subprocess.run([str(args.trace_processor), 'query', str(trace), query(pid)], capture_output=True, text=True, check=True)
            tables = parse_tables(proc.stdout)
            if len(tables) != 11:
                raise ValueError(f'{trace}: expected 11 tables; got {len(tables)}')
            health, stages, frames, timeline, threads, stage_cpu, counters, inputs, bounds, revisions, gpu = tables
            expected_trace_seconds = 6 if phase == 'cold' else 4 if phase == 'idle' else 8
            trace_seconds = (bounds[0]['end_ts'] - bounds[0]['start_ts']) / 1e9
            if trace_seconds < expected_trace_seconds - 0.25:
                flags.append(f'short_trace:{trace_seconds:.3f}s')
            if any(x['severity'] == 'data_loss' or 'overrun_delta' in x['name'] for x in health):
                flags.append('trace_data_loss')
            if any(x['incomplete'] for x in stages) or any(x['dur'] < 0 for x in frames):
                flags.append('incomplete_material_or_ui_slice')
            completed = [x for x in frames if x['dur'] >= 0]
            completed_timeline = [x for x in timeline if x['dur'] >= 0]
            gestures_down = sum('ACTION_DOWN' in x['name'] for x in inputs)
            gestures_up = sum('ACTION_UP' in x['name'] for x in inputs)
            expected_gestures = config['drag_count'] + config['fling_count'] if phase in ('down', 'revisit') else 0
            if (gestures_down, gestures_up) != (expected_gestures, expected_gestures):
                flags.append(f'input_count_mismatch:{gestures_down}/{gestures_up},expected={expected_gestures}')
            frame_map = {x['frame_id']: x for x in frames}
            requests = {x['name'].removeprefix('SeaGlass.Request:'): x for x in revisions if x['name'].startswith('SeaGlass.Request:')}
            revision_rows = []
            for event in revisions:
                if not event['name'].startswith('SeaGlass.Applied:'):
                    continue
                key = event['name'].removeprefix('SeaGlass.Applied:')
                request = requests.get(key)
                present = frame_map.get(event['frame_id'], {}).get('display_present_ns')
                revision_rows.append({'key': key, 'requestNs': request['ts'] if request else None,
                                      'appliedNs': event['ts'], 'presentNs': present,
                                      'requestToPresentMs': (present-request['ts'])/1e6 if present and request else None})
            stage_map = {x['name']: x for x in stages}
            diagnostic_events = {}
            for stage in stages:
                if not stage['name'].startswith('SeaGlass.') or ':' not in stage['name']:
                    continue
                kind, owner, payload = stage['name'].removeprefix('SeaGlass.').split(':', 2)
                if kind in ('Request', 'Applied'):
                    continue
                event = diagnostic_events.setdefault(kind, {'count': 0, 'payloadSum': 0})
                event['count'] += stage['count']
                value = number(payload)
                if value is not None:
                    event['payloadSum'] += value * stage['count']
            build_count = stage_map.get('SeaGlass.Geometry', {}).get('count', 0)
            delta = runtime_delta(before, after) if before else None
            capture = {'label': label, 'phase': phase, 'pid': pid, 'flags': flags,
                       'traceSha256': hashlib.sha256(trace.read_bytes()).hexdigest(), 'traceBounds': bounds[0],
                       'traceHealth': health, 'sceneBefore': before['scrollScene'] if before else None,
                       'sceneAfter': scene_after, 'runtimeDelta': delta,
                       'runtimeAfterGauges': {key: after.get(key) for key in ('heapUsedBytes', 'heapTotalBytes', 'heapMaxBytes', 'nativeAllocatedBytes', 'nativeSizeBytes', 'nativeFreeBytes')},
                       'meminfo': memory(Path(f'{prefix}.meminfo')), 'stages': stages,
                       'diagnosticEvents': diagnostic_events,
                       'scheduledAppCpuMs': sum(x['cpu_ms'] for x in threads), 'threads': threads,
                       'stageScheduledCpu': stage_cpu, 'memoryCountersRaw': counters, 'gpuObservability': gpu[0],
                       'buildCount': build_count,
                       'uploadCount': stage_map.get('SeaGlass.TextureUpload', {}).get('count', 0),
                       'programCreateCount': stage_map.get('SeaGlass.ProgramCreate', {}).get('count', 0),
                       'uiFrames': {name: distribution([x['dur']/1e6 for x in completed if predicate(x)], percentile)
                                    for name, predicate in [('all', lambda x: True), ('instrumented', lambda x: x['instrumented'] > 0), ('upload', lambda x: x['uploads'] > 0)]},
                       'frameTimeline': {'completedRows': len(completed_timeline), 'incompleteRows': len(timeline)-len(completed_timeline),
                                         'appDeadlineMissedRows': sum('App Deadline Missed' in (x['jank_type'] or '') for x in completed_timeline),
                                         'droppedRows': sum(x['present_type'] == 'Dropped Frame' for x in completed_timeline)},
                       'gfxinfo': analyze_gfx(Path(f'{prefix}.gfxinfo').read_text(), str(prefix))['summary'],
                       'inputDownCount': gestures_down, 'inputUpCount': gestures_up,
                       'requests': len(requests), 'applied': len(revision_rows), 'revisionRows': revision_rows,
                       'requestToPresentation': distribution([x['requestToPresentMs'] for x in revision_rows if x['requestToPresentMs'] is not None], percentile)}
            if delta and build_count:
                allocated = delta['art']['art.gc.bytes-allocated']
                capture['allocatedBytesPerBuild'] = allocated/build_count if allocated is not None else None
                capture['processCpuMsPerBuild'] = delta['processCpuTimeMs']/build_count
            captures.append(capture)
        block_results.append({'label': label, 'pid': pid, 'elapsedSeconds': elapsed, 'flags': issues})
    endpoint_comparisons = {}
    for phase in ('cold', 'down', 'revisit', 'idle'):
        endpoints = [{'label': item['label'], 'position': position(item['sceneAfter']),
                      'visibleKeys': item['sceneAfter'].get('visibleKeys')}
                     for item in captures if item['phase'] == phase]
        signatures = {json.dumps({key: value for key, value in item.items() if key != 'label'}, sort_keys=True)
                      for item in endpoints}
        endpoint_comparisons[phase] = {'identicalAcrossBlocks': len(signatures) <= 1,
                                       'endpoints': endpoints,
                                       'note': 'Exact endpoint equality does not prove identical intermediate trajectories.'}
    report = {'blocks': block_results, 'captures': captures, 'endpointComparisons': endpoint_comparisons,
              'notes': ['All trace metrics are full phase windows; runtime deltas include the bracketing observer costs.',
                        'Geometry and upload counts are invocations, not distinct geometry keys or native objects.',
                        'Lazy list requests without applied markers can be detached, superseded or outside the window.',
                        'Compare actual scene positions/visible keys across variants before attributing differences.',
                        'No forced GC; memory endpoints are process gauges and do not prove retained texture bytes.',
                        'NativeBoundInput counters describe logical open-owner bindings only; recorded frames/native owners can outlive them. Per-owner samples may omit already-open owners when a phase starts.',
                        'Per-capture flags must be reviewed; do not blindly average invalid captures.',
                        'Emulator CPU/driver evidence is not physical GPU, power or thermal evidence.']}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(args.output)
    for row in captures:
        print(row['label'], row['phase'], 'builds', row['buildCount'], 'uploads', row['uploadCount'],
              'cpuMs', round(row['scheduledAppCpuMs'], 2), 'flags', row['flags'])


if __name__ == '__main__':
    main()
