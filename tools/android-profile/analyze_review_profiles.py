#!/usr/bin/env python3
"""Analyze saved captures, keeping worker time and UI frame populations separate."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys

WORK = Path(__file__).resolve().parent
sys.path.insert(0, str(WORK))
from analyze_profile_captures import SQL as BASE_SQL, parse_tables
from analyze_framestats import analyze, percentile

PACKAGE = 'dev.mttmcknn.seaglass.sample'
EXTRA_SQL = """
SELECT s.id,s.ts,s.dur,s.name,t.name AS thread_name,t.tid,p.pid,
       CASE WHEN t.tid=p.pid THEN 1 ELSE 0 END AS is_ui,
       (SELECT a.id FROM ancestor_slice(s.id) a WHERE a.name GLOB 'Choreographer#doFrame [0-9]*' LIMIT 1) AS frame_id
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='dev.mttmcknn.seaglass.sample' AND s.name GLOB 'SeaGlass.*' ORDER BY s.ts;

WITH app_timeline AS (
 SELECT f.surface_frame_token,MAX(f.ts+f.dur) AS app_complete_ns,
        MAX(CASE WHEN sf.dur>=0 AND f.present_type!='Dropped Frame' THEN sf.ts+sf.dur END) AS display_present_ns,
        COUNT(*) AS timeline_rows,
        SUM(CASE WHEN f.jank_type GLOB '*App Deadline Missed*' THEN 1 ELSE 0 END) AS app_deadline_misses,
        SUM(CASE WHEN f.present_type='Dropped Frame' THEN 1 ELSE 0 END) AS dropped_rows
 FROM actual_frame_timeline_slice f JOIN process p USING(upid)
 LEFT JOIN actual_frame_timeline_slice sf
   ON sf.display_frame_token=f.display_frame_token AND (sf.surface_frame_token=0 OR sf.surface_frame_token IS NULL) AND sf.layer_name IS NULL
 WHERE p.name='dev.mttmcknn.seaglass.sample' AND f.dur>=0 AND f.layer_name GLOB '*MainActivity*'
 GROUP BY f.surface_frame_token
)
SELECT d.id AS frame_id,d.ts,d.dur,d.name,a.app_complete_ns,a.display_present_ns,
       a.timeline_rows,a.app_deadline_misses,a.dropped_rows
FROM slice d JOIN thread_track tt ON tt.id=d.track_id
JOIN thread t USING(utid) JOIN process p USING(upid)
LEFT JOIN app_timeline a ON a.surface_frame_token=CAST(substr(d.name,instr(d.name,' ')+1) AS INT)
WHERE p.name='dev.mttmcknn.seaglass.sample' AND t.tid=p.pid AND d.name GLOB 'Choreographer#doFrame [0-9]*' ORDER BY d.ts;

SELECT s.id,s.ts,s.dur,s.name,
       (SELECT CAST(substr(d.name,instr(d.name,'eventTimeNano=')+14) AS INT)
        FROM descendant_slice(s.id) d WHERE d.name GLOB 'deliverInputEvent *eventTimeNano=*' LIMIT 1) AS source_event_time_ns
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='dev.mttmcknn.seaglass.sample' AND t.tid=p.pid
 AND s.name GLOB 'dispatchInputEvent MotionEvent ACTION_*' ORDER BY s.ts;

SELECT * FROM trace_bounds;
"""


def distribution(values):
    return {'count':len(values), 'p50_ms':percentile(values,.5), 'p95_ms':percentile(values,.95),
            'max_ms':max(values) if values else None, 'total_ms':sum(values)}


def stage_summary(events):
    groups=defaultdict(list)
    for e in events:
        groups[(e['name'],'ui' if e['is_ui'] else 'worker')].append(e)
    rows=[]
    for (name,thread),items in sorted(groups.items()):
        rows.append({'name':name,'thread':thread,'slice_count':len(items),
                     'incomplete_count':sum(e['dur']<0 for e in items),
                     **distribution([e['dur']/1e6 for e in items if e['dur']>=0])})
    return rows


def frame_summary(frames):
    valid=[f for f in frames if f['dur']>=0]
    out={'frame_count':len(valid),'incomplete_frame_count':len(frames)-len(valid),
         'ui_do_frame':distribution([f['dur']/1e6 for f in valid])}
    for name in ('Geometry','TextureUpload','ProgramCreate'):
        out[name]={'count':sum(f['costs'].get(name,{}).get('count',0) for f in valid),
                   **distribution([f['costs'].get(name,{}).get('ms',0) for f in valid])}
        # Distribution count is frame count; retain stage invocation count separately.
        out[name]['invocation_count']=sum(f['costs'].get(name,{}).get('count',0) for f in valid)
    out['timeline_app_deadline_missed_rows']=sum(f.get('app_deadline_misses') or 0 for f in valid)
    out['timeline_rows']=sum(f.get('timeline_rows') or 0 for f in valid)
    out['timeline_app_deadline_missed_percent']=100*out['timeline_app_deadline_missed_rows']/out['timeline_rows'] if out['timeline_rows'] else None
    out['timeline_dropped_rows']=sum(f.get('dropped_rows') or 0 for f in valid)
    return out


def populations(frames):
    return {'all_ui_frames':frame_summary(frames),
            'instrumented_ui_frames':frame_summary([f for f in frames if f['instrumented_count']>0]),
            'material_upload_frames':frame_summary([f for f in frames if f['costs'].get('TextureUpload',{}).get('count',0)>0])}


def gestures_from(inputs):
    gestures=[]; current=None
    for e in inputs:
        action=e['name'].split('ACTION_',1)[1].split()[0]
        if action=='DOWN':
            current={'down_ns':e['ts'],'down_event_ns':e['source_event_time_ns'],'last_move_ns':None,'last_move_event_ns':None,'up_ns':None,'up_event_ns':None,'move_event_count':0}
            gestures.append(current)
        elif current and action=='MOVE':
            current['last_move_ns']=e['ts']; current['last_move_event_ns']=e['source_event_time_ns']; current['move_event_count']+=1
        elif current and action=='UP':
            current['up_ns']=e['ts']; current['up_event_ns']=e['source_event_time_ns']; current=None
    return gestures


def matching_revision_latency(events,frames):
    requests={}; applied=[]; frame_map={f['frame_id']:f for f in frames}
    for e in events:
        if e['name'].startswith('SeaGlass.Request:'):
            requests[e['name'].removeprefix('SeaGlass.Request:')]=e
        elif e['name'].startswith('SeaGlass.Applied:'):
            key=e['name'].removeprefix('SeaGlass.Applied:')
            request=requests.get(key)
            frame=frame_map.get(e['frame_id'])
            applied.append({'revision_key':key,'request_ns':request['ts'] if request else None,
                            'applied_ns':e['ts'],'frame_id':e['frame_id'],
                            'display_present_ns':frame.get('display_present_ns') if frame else None})
    for e in applied:
        e['request_to_applied_ms']=(e['applied_ns']-e['request_ns'])/1e6 if e['request_ns'] else None
        e['request_to_display_ms']=(e['display_present_ns']-e['request_ns'])/1e6 if e['request_ns'] and e['display_present_ns'] else None
    applied_keys={e['revision_key'] for e in applied}
    return {'request_count':len(requests),'applied_count':len(applied),
            'requests_without_applied_marker':len(set(requests)-applied_keys),
            'request_to_applied':distribution([e['request_to_applied_ms'] for e in applied if e['request_to_applied_ms'] is not None]),
            'request_to_display':distribution([e['request_to_display_ms'] for e in applied if e['request_to_display_ms'] is not None]),
            'rows':applied}


def analyze_capture(trace,tp):
    result=subprocess.run([str(tp),'query',str(trace),BASE_SQL+EXTRA_SQL],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
    tables=parse_tables(result.stdout)
    if len(tables)!=11: raise RuntimeError(f'{trace.name}: expected 11 tables, got {len(tables)}')
    events,frames,inputs,bounds=tables[7],tables[8],tables[9],tables[10][0]
    frame_map={f['frame_id']:f for f in frames}
    for f in frames: f['costs']={}; f['instrumented_count']=0
    for e in events:
        f=frame_map.get(e['frame_id'])
        if f is None or not e['is_ui']: continue
        f['instrumented_count']+=1
        if e['dur']<0: continue
        cost=f['costs'].setdefault(e['name'].removeprefix('SeaGlass.'),{'count':0,'ms':0})
        cost['count']+=1; cost['ms']+=e['dur']/1e6
    gestures=gestures_from(inputs)
    expected=8 if '-switch-' in trace.name else 2
    scripted=gestures[:expected]
    completed=[g for g in scripted if g['up_ns'] is not None]
    start=scripted[0]['down_ns'] if scripted else bounds['start_ts']
    end=min(bounds['end_ts'],completed[-1]['up_ns']+1_000_000_000) if completed else bounds['end_ts']
    if len(gestures)>expected: end=min(end,gestures[expected]['down_ns'])
    primary_frames=[f for f in frames if start<=f['ts']<end]
    primary_events=[e for e in events if start<=e['ts']<end]
    revision_latency=matching_revision_latency(primary_events,primary_frames)
    applied_by_key={row['revision_key']:row for row in revision_latency['rows']}
    for index,g in enumerate(scripted):
        limit=scripted[index+1]['down_ns'] if index+1<len(scripted) else end
        latest_by_surface={}
        for event in primary_events:
            if g['down_ns']<=event['ts']<limit and event['name'].startswith('SeaGlass.Request:'):
                revision_key=event['name'].removeprefix('SeaGlass.Request:')
                latest_by_surface[revision_key.rsplit(':',1)[0]]=revision_key
        cohort=[{'revision_key':key, 'matched_applied':key in applied_by_key,
                 'display_present_ns':applied_by_key.get(key,{}).get('display_present_ns')} for key in latest_by_surface.values()]
        g['latest_requested_revisions']=cohort
        g['latest_requested_surface_count']=len(cohort)
        g['latest_requested_all_applied']=all(row['matched_applied'] for row in cohort) if cohort else None
        g['latest_requested_all_presented']=all(row['display_present_ns'] is not None for row in cohort) if cohort else None
        present=max(row['display_present_ns'] for row in cohort) if cohort and g['latest_requested_all_presented'] else None
        g['last_move_to_latest_requested_cohort_present_ms']=(present-g['last_move_ns'])/1e6 if present and g['last_move_ns'] else None
        g['up_to_latest_requested_cohort_present_ms']=(present-g['up_ns'])/1e6 if present and g['up_ns'] else None
        g['last_move_event_to_latest_requested_cohort_present_ms']=(present-g['last_move_event_ns'])/1e6 if present and g['last_move_event_ns'] else None
        g['up_event_to_latest_requested_cohort_present_ms']=(present-g['up_event_ns'])/1e6 if present and g['up_event_ns'] else None
        changed=[f for f in primary_frames if g['down_ns']<=f['ts']<limit and f['costs'].get('TextureUpload',{}).get('count',0)>0]
        last=changed[-1] if changed else None
        g['upload_frame_count']=len(changed)
        g['upload_count']=sum(f['costs']['TextureUpload']['count'] for f in changed)
        g['last_upload_frame_id']=last['frame_id'] if last else None
        g['last_upload_frame_present_ns']=last['display_present_ns'] if last else None
        g['last_upload_frame_surface_count']=last['costs']['TextureUpload']['count'] if last else None
        for label,stamp in [('last_move',g['last_move_ns']),('up',g['up_ns']),('last_move_event',g['last_move_event_ns']),('up_event',g['up_event_ns'])]:
            g[f'{label}_to_last_upload_present_ms']=(last['display_present_ns']-stamp)/1e6 if last and last['display_present_ns'] and stamp else None
    gfx=analyze(trace.with_suffix('.gfxinfo').read_text(),str(trace.with_suffix('.gfxinfo').resolve()))
    frame_rows=[r for block in gfx['blocks'] for r in block['frames'] if r['valid']]
    gfx_window=[r for r in frame_rows if start<=r['metrics'].get('IntendedVsync',0)<end]
    from analyze_framestats import summarize
    report={'name':trace.stem,'trace_sha256':hashlib.sha256(trace.read_bytes()).hexdigest(),
            'trace_bounds':bounds,'trace_health':tables[3],
            'primary_window':{'start_ns':start,'end_ns':end,'duration_ms':(end-start)/1e6,
                              'rule':f'First {expected} input gestures, through final ACTION_UP + 1000 ms; exclude any subsequent DOWN.',
                              'scripted_gesture_count':len(scripted),'extra_gesture_count':max(0,len(gestures)-expected),
                              **populations(primary_frames),'stages_by_thread':stage_summary(primary_events),
                              'gfxinfo':summarize(gfx_window)},
            'full_capture':{**populations(frames),'stages_by_thread':stage_summary(events),'gfxinfo':gfx['summary'],
                            'frame_timeline':tables[1][0],'frame_timeline_classifications':tables[2],'native_pipeline':tables[5]},
            'scripted_gestures':scripted,'all_gestures':gestures,'gfxinfo_warnings':gfx['warnings'],
            'revision_latency':revision_latency,'ui_frames':frames}
    trace.with_name(trace.stem+'.review-trace-analysis.log').write_text(result.stderr)
    return report


def summarize_workloads(captures):
    groups=defaultdict(list)
    for capture in captures:
        workload=capture['name'].rsplit('-',2)[1]
        groups[workload].append(capture)
    result={}
    for workload,items in groups.items():
        values=defaultdict(list)
        for capture in items:
            w=capture['primary_window']
            for population in ('all_ui_frames','instrumented_ui_frames','material_upload_frames'):
                frame=w[population]
                values[population+'.frame_count'].append(frame['frame_count'])
                for metric in ('p50_ms','p95_ms','max_ms'):
                    val=frame['ui_do_frame'][metric]
                    if val is not None: values[population+'.ui_do_frame.'+metric].append(val)
            upload=w['material_upload_frames']
            for stage in ('Geometry','TextureUpload','ProgramCreate'):
                values[stage+'.invocation_count'].append(upload[stage]['invocation_count'])
                for metric in ('p50_ms','p95_ms'):
                    val=upload[stage][metric]
                    if val is not None: values['upload_frames.'+stage+'.'+metric].append(val)
            g=w['gfxinfo']; values['gfx.valid_frames'].append(g['valid_frame_count'])
            if g['completion_deadline']['missed_percent'] is not None:
                values['gfx.deadline_missed_percent'].append(g['completion_deadline']['missed_percent'])
            for metric in ('p50_ms','p95_ms'):
                if g['duration'][metric] is not None: values['gfx.duration.'+metric].append(g['duration'][metric])
            worker=next((e for e in w['stages_by_thread'] if e['name']=='SeaGlass.Geometry' and e['thread']=='worker'),{})
            values['worker_geometry.count'].append(worker.get('count',0))
            values['worker_geometry.total_ms'].append(worker.get('total_ms',0))
        result[workload]={'capture_count':len(items),'captures':[c['name'] for c in items],
                          'per_run_metric_summary':{key:{'median':statistics.median(v),'min':min(v),'max':max(v),'values':v} for key,v in sorted(values.items())}}
    return result


def f(value): return '—' if value is None else f'{value:.2f}'


def render_report(report):
    lines=['# Review performance captures','',
           'Primary windows include the scripted gestures and one second after the final ACTION_UP. Full-capture populations are retained separately in JSON. All durations are milliseconds. Counts are reported to expose skipped/coalesced work.','',
           '| Capture | All UI frames | Instrumented frames | Upload frames / uploads | UI doFrame p50/p95, instrumented | UI doFrame p50/p95, upload | UI geometry p50/p95, upload | Worker geometry count / total ms | gfx frames | gfx deadline misses |',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for c in report['captures']:
        w=c['primary_window']; a=w['all_ui_frames']; i=w['instrumented_ui_frames']; u=w['material_upload_frames']; g=w['gfxinfo']
        worker=next((s for s in w['stages_by_thread'] if s['name']=='SeaGlass.Geometry' and s['thread']=='worker'),{})
        lines.append(f"| {c['name']} | {a['frame_count']} | {i['frame_count']} | {u['frame_count']} / {u['TextureUpload']['invocation_count']} | {f(i['ui_do_frame']['p50_ms'])} / {f(i['ui_do_frame']['p95_ms'])} | {f(u['ui_do_frame']['p50_ms'])} / {f(u['ui_do_frame']['p95_ms'])} | {f(u['Geometry']['p50_ms'])} / {f(u['Geometry']['p95_ms'])} | {worker.get('count',0)} / {f(worker.get('total_ms',0))} | {g['valid_frame_count']} | {g['completion_deadline']['missed_count']}/{g['completion_deadline']['eligible_frame_count']} ({f(g['completion_deadline']['missed_percent'])}%) |")
    lines+=['','## Gesture settlement proxy','',
            'These timestamps identify the final upload-containing frame before the next gesture. They do not prove that every surface used the final requested value. The display timestamp joins that app frame to its SurfaceFlinger display frame; missing matches remain missing. Negative ACTION_UP deltas mean the last upload was already presented before release. Frost/light have no geometry upload, so this proxy does not apply.','',
            '| Capture | Gesture | MOVE events | Upload frames / uploads | Last frame surfaces | Last MOVE → last upload presentation | UP → last upload presentation |','|---|---:|---:|---:|---:|---:|---:|']
    for c in report['captures']:
        for n,g in enumerate(c['scripted_gestures'],1):
            if g['upload_count']==0: continue
            lines.append(f"| {c['name']} | {n} | {g['move_event_count']} | {g['upload_frame_count']} / {g['upload_count']} | {g['last_upload_frame_surface_count']} | {f(g['last_move_to_last_upload_present_ms'])} | {f(g['up_to_last_upload_present_ms'])} |")
    lines+=['','## Qualifications','']+['- '+n for n in report['metric_notes']]
    for c in report['captures']:
        if c['primary_window']['extra_gesture_count']:
            lines.append(f"- {c['name']}: {c['primary_window']['extra_gesture_count']} later input gesture(s) outside the scripted window; preserved in full-capture statistics.")
        bad=[s for s in c['trace_health'] if s['severity']=='data_loss']
        if bad: lines.append(f"- {c['name']}: trace data-loss flags: {bad}")
    return '\n'.join(lines)+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path,nargs='?',default=Path(__file__).resolve().parent)
    parser.add_argument('--pattern',nargs='+',default=['review-before-*.perfetto-trace'])
    parser.add_argument('--name',default='review-before-analysis')
    parser.add_argument('--trace-processor',type=Path,required=True)
    args=parser.parse_args()
    captures=[]
    for trace in sorted({trace for pattern in args.pattern for trace in args.directory.glob(pattern)}):
        if not trace.with_suffix('.gfxinfo').exists(): continue
        captures.append(analyze_capture(trace,args.trace_processor)); print('Analyzed '+trace.stem,flush=True)
    report={'metric_notes':[
        'Geometry on non-main app threads is worker time; it is never charged to UI doFrame. Durations are wall-clock slices, including descheduling.',
        'Instrumented frames contain any SeaGlass slice. Upload frames contain TextureUpload even on geometry-cache hits. Both populations and all app UI frames are included.',
        'Frame populations include top-level numbered Choreographer#doFrame spans; nested resynced diagnostic spans are excluded to avoid double counting. Frames use start timestamps in the same gesture window; incomplete spans are counted separately and excluded from percentiles.',
        'gfxinfo completion is FrameCompleted minus IntendedVsync. Deadline misses compare FrameCompleted > FrameDeadline with an explicit eligible-frame denominator; these are not display FPS.',
        'gfxinfo rings may truncate scene-switch history. Trace populations and FrameTimeline are retained; do not substitute the smaller ring population for a complete workload.',
        'Gesture settlement is an unversioned last-upload presentation proxy. Exact final-state visibility needs paired request/applied revision markers for every surface. Both app-dispatch timestamps and original injected MotionEvent eventTimeNano timestamps are retained; dispatch latency includes UI input-queue delay.',
        'App FrameTimeline ends at GPU/post completion; presentation uses the linked SurfaceFlinger frame end. Source: https://perfetto.dev/docs/data-sources/frametimeline',
        'Revision request markers without an applied marker may be superseded, cancelled, detached, or outside the window; the trace alone cannot label them discarded incorrectly.'
    ],'captures':captures}
    report['workload_summary']=summarize_workloads(captures)
    (args.directory/(args.name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
    (args.directory/(args.name+'.md')).write_text(render_report(report))
    print(f'Wrote {len(captures)} captures to {args.directory/args.name}.json/.md')

if __name__=='__main__':main()
