#!/usr/bin/env python3
"""Compare LiveGeometryBenchmark JSONs; no builds, workloads, or JFR processing."""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics


def stats(values):
    available = sorted(v for v in values if v is not None)
    result = {'observations': len(values), 'available': len(available)}
    result.update({k: None for k in ('median', 'p95', 'min', 'max')})
    if available:
        result.update(median=statistics.median(available), p95=available[math.ceil(.95 * len(available)) - 1],
                      min=available[0], max=available[-1])
    return result


def ratio(after, before):
    return after / before if after is not None and before not in (None, 0) else None


def difference(before, after):
    return before - after if before is not None and after is not None else None


def combined(sample, metric):
    a, b = sample['geometry'][metric], sample['upload'][metric]
    return a + b if a is not None and b is not None else None


def vm_args(report):
    return [a.split('filename=')[0] + 'filename=<OUTPUT>,' + a.split(',', 1)[1]
            if a.startswith('-XX:StartFlightRecording=filename=') else a for a in report['vmArguments']]


def payload(sample, field):
    if field == 'retainedContourBasisBytes':
        # Older harnesses retain no basis and omit this field. Accept the early
        # prototype spelling as an alias, without concealing conflicting values.
        if 'retainedContourBasisBytes' in sample and 'contourBasisCachePayloadBytes' in sample:
            assert sample['retainedContourBasisBytes'] == sample['contourBasisCachePayloadBytes']
        return sample.get('retainedContourBasisBytes', sample.get('contourBasisCachePayloadBytes', 0))
    return sample[field]


def payload_reported(sample, field):
    return field in sample or (field == 'retainedContourBasisBytes' and 'contourBasisCachePayloadBytes' in sample)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, help='Contains each variant directory and its kernel logs/JSONs')
    parser.add_argument('--before', required=True)
    parser.add_argument('--after', required=True)
    parser.add_argument('--rounds', default='1,2', help='Comma-separated integer run IDs')
    parser.add_argument('--expected-samples', type=int)
    parser.add_argument('--expected-warmups', type=int)
    parser.add_argument('--require-equal-payloads', default='texturePayloadBytes,cloudCachePayloadBytes,nativeRasterPayloadBytes',
                        help='Comma-separated fields required unchanged; empty string allows changes')
    parser.add_argument('--output', type=Path, help='Defaults to DIRECTORY/jvm-comparison')
    args = parser.parse_args()
    root = args.directory.resolve()
    out = args.output.resolve() if args.output else root / 'jvm-comparison'
    rounds = tuple(int(r) for r in args.rounds.split(','))
    assert len(set(rounds)) == len(rounds) and rounds
    variants = (args.before, args.after)
    assert variants[0] != variants[1]
    required_equal = tuple(f for f in args.require_equal_payloads.split(',') if f)
    reports, inputs = {}, []
    for variant in variants:
        for round_ in rounds:
            path = root / variant / f'live-geometry-{round_}.json'
            log = root / variant / f'kernel-{round_}.log'
            if not path.exists() or not log.exists() or 'BUILD SUCCESSFUL' not in log.read_text():
                raise SystemExit(f'Waiting for completed run: {variant} round {round_}; no output written.')
            reports[variant, round_] = json.loads(path.read_text())
            inputs.append({'path': str(path.relative_to(root)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    reference = reports[args.before, rounds[0]]
    metadata = ('schemaVersion', 'workloadVersion', 'fieldScale', 'samples', 'warmupsPerCase', 'initialWarmupsPerCase',
                'javaVersion', 'vmName', 'os', 'availableProcessors', 'threadCpuTimeAvailable',
                'threadAllocatedBytesAvailable', 'oracle')
    if args.expected_samples is not None:
        assert reference['samples'] == args.expected_samples
    if args.expected_warmups is not None:
        assert reference['warmupsPerCase'] == args.expected_warmups
    names = [c['name'] for c in reference['cases']]
    for report in reports.values():
        assert all(report[k] == reference[k] for k in metadata), 'Metadata changed'
        assert vm_args(report) == vm_args(reference), 'JVM settings changed'
        assert [c['name'] for c in report['cases']] == names
        for expected, actual in zip(reference['cases'], report['cases']):
            assert all(expected[k] == actual[k] for k in ('name', 'componentCount', 'reuseClouds', 'fixtures'))
            assert len(actual['samples']) == reference['samples']
            assert [s['tumble'] for s in actual['samples']] == [s['tumble'] for s in expected['samples']]
    # Policy changes between variants are intentional experimental inputs. They
    # remain explicit, while policy drift between rounds of one variant fails.
    build_cache_policies = {}
    for variant in variants:
        initial = reports[variant, rounds[0]]
        build_cache_policies[variant] = {'reported': 'buildCachePolicy' in initial,
                                        'buildCachePolicy': initial.get('buildCachePolicy')}
        for round_ in rounds:
            report = reports[variant, round_]
            assert report.get('buildCachePolicy') == initial.get('buildCachePolicy'), (variant, 'cache policy changed between rounds')
            for expected, actual in zip(initial['cases'], report['cases']):
                assert actual.get('reuseContourBasis', False) == expected.get('reuseContourBasis', False), (variant, actual['name'], 'basis reuse changed between rounds')

    def values(variant, selected_rounds, case_index, stage, metric):
        samples = [s for r in selected_rounds for s in reports[variant, r]['cases'][case_index]['samples']]
        return [combined(s, metric) if stage == 'combined' else s[stage][metric] for s in samples]

    pair_rows, phase_rows, memory_rows, policy_rows, cases, repeated_states = [], [], [], [], [], {}
    fields = ('texturePayloadBytes', 'geometryCacheEstimateBytes', 'cloudCachePayloadBytes', 'nativeRasterPayloadBytes', 'retainedContourBasisBytes')
    for i, name in enumerate(names):
        case_pairs = []
        case_policy = {}
        for variant in variants:
            source = reports[variant, rounds[0]]['cases'][i]
            case_policy[variant] = {'reuseClouds': source['reuseClouds'],
                                    'reuseContourBasis': source.get('reuseContourBasis', False),
                                    'reuseContourBasisReported': 'reuseContourBasis' in source}
        policy_rows.append({'case': name,
                            'before_build_cache_policy': build_cache_policies[args.before]['buildCachePolicy'],
                            'after_build_cache_policy': build_cache_policies[args.after]['buildCachePolicy'],
                            'before_reuse_clouds': case_policy[args.before]['reuseClouds'],
                            'after_reuse_clouds': case_policy[args.after]['reuseClouds'],
                            'before_reuse_contour_basis': case_policy[args.before]['reuseContourBasis'],
                            'after_reuse_contour_basis': case_policy[args.after]['reuseContourBasis'],
                            'before_reuse_contour_basis_reported': case_policy[args.before]['reuseContourBasisReported'],
                            'after_reuse_contour_basis_reported': case_policy[args.after]['reuseContourBasisReported']})
        fixture_key = json.dumps(reference['cases'][i]['fixtures'], sort_keys=True)
        for round_ in rounds:
            before = reports[args.before, round_]['cases'][i]['samples']
            after = reports[args.after, round_]['cases'][i]['samples']
            for index, (b, a) in enumerate(zip(before, after)):
                assert b['inputChecksum'] == a['inputChecksum'], (name, round_, index, 'F16 mismatch')
                assert b['geometryChecksum'] == a['geometryChecksum'], (name, round_, index, 'geometry mismatch')
                for field in required_equal:
                    assert payload(b, field) == payload(a, field), (name, round_, index, field, 'required-equal payload mismatch')
                for sample in (b, a):
                    key = fixture_key, sample['tumble']
                    checksums = sample['inputChecksum'], sample['geometryChecksum']
                    if key in repeated_states:
                        assert repeated_states[key] == checksums, ('repeated state mismatch', key)
                    repeated_states[key] = checksums
                row = {'case': name, 'round': round_, 'sample_index': index, 'tumble': b['tumble'],
                       'f16_equal': True, 'geometry_equal': True}
                for stage in ('geometry', 'upload', 'combined'):
                    bv = combined(b, 'allocatedBytes') if stage == 'combined' else b[stage]['allocatedBytes']
                    av = combined(a, 'allocatedBytes') if stage == 'combined' else a[stage]['allocatedBytes']
                    row[f'before_{stage}_allocated_bytes'] = bv
                    row[f'after_{stage}_allocated_bytes'] = av
                    row[f'{stage}_allocated_bytes_saved'] = difference(bv, av)
                for field in fields:
                    row[f'before_{field}'], row[f'after_{field}'] = payload(b, field), payload(a, field)
                    row[f'before_{field}_reported'], row[f'after_{field}_reported'] = payload_reported(b, field), payload_reported(a, field)
                pair_rows.append(row)
                case_pairs.append(row)
        entry = {'name': name, 'componentCount': reference['cases'][i]['componentCount'],
                 'buildCachePolicy': {'before': case_policy[args.before], 'after': case_policy[args.after]},
                 'allocationDeltas': {}, 'phases': {}, 'reportedMemory': {}}
        for stage in ('geometry', 'upload', 'combined'):
            deltas = [p[f'{stage}_allocated_bytes_saved'] for p in case_pairs]
            entry['allocationDeltas'][stage] = {**stats(deltas), 'valueCounts': dict(sorted(collections.Counter(v for v in deltas if v is not None).items()))}
            phase_groups = []
            for selected, label in [(tuple([r]), str(r)) for r in rounds] + [(rounds, 'pooled')]:
                metrics = {variant: {metric: stats(values(variant, selected, i, stage, metric))
                                     for metric in ('wallNs', 'cpuNs', 'allocatedBytes')} for variant in variants}
                phase_groups.append({'round': label, 'before': metrics[args.before], 'after': metrics[args.after]})
                row = {'case': name, 'phase': stage, 'round': label, 'observations_per_variant': metrics[args.before]['wallNs']['observations']}
                for metric, suffix, divisor in [('wallNs', 'wall_ms', 1e6), ('cpuNs', 'cpu_ns', 1), ('allocatedBytes', 'allocated_bytes', 1)]:
                    for stat in ('median', 'p95'):
                        bv, av = metrics[args.before][metric][stat], metrics[args.after][metric][stat]
                        row[f'before_{stat}_{suffix}'] = bv/divisor if bv is not None else None
                        row[f'after_{stat}_{suffix}'] = av/divisor if av is not None else None
                        row[f'{stat}_{suffix}_after_over_before'] = ratio(av, bv)
                phase_rows.append(row)
            entry['phases'][stage] = phase_groups
        for field in fields:
            b = stats([p[f'before_{field}'] for p in case_pairs])
            a = stats([p[f'after_{field}'] for p in case_pairs])
            equal = all(p[f'before_{field}'] == p[f'after_{field}'] for p in case_pairs)
            before_reported = sum(p[f'before_{field}_reported'] for p in case_pairs)
            after_reported = sum(p[f'after_{field}_reported'] for p in case_pairs)
            entry['reportedMemory'][field] = {'before': b, 'after': a, 'allPairsEqual': equal,
                                             'beforeReportedSamples': before_reported, 'afterReportedSamples': after_reported}
            memory_rows.append({'case': name, 'field': field, 'before_median': b['median'], 'after_median': a['median'],
                                'all_pairs_equal': equal, 'required_equal': field in required_equal,
                                'before_reported_samples': before_reported, 'after_reported_samples': after_reported})
        cases.append(entry)
    result = {'schemaVersion': 1, 'beforeVariant': args.before, 'afterVariant': args.after, 'inputs': inputs,
              'metadata': {k: reference[k] for k in metadata}, 'normalizedVmArguments': vm_args(reference),
              'buildCachePolicies': {'before': build_cache_policies[args.before], 'after': build_cache_policies[args.after]},
              'parity': {'pairedSamples': len(pair_rows), 'pairedChecksumComparisons': 2*len(pair_rows),
                         'allF16Equal': True, 'allGeometryEqual': True, 'requiredEqualPayloads': required_equal,
                         'distinctFixtureTumbleStates': len(repeated_states)},
              'allocationObjectCountsAvailable': False,
              'scope': 'ThreadMXBean reports allocated heap BYTES on the current thread, not object counts. No model or expected byte-saving assumption is used. Native allocation and other threads are excluded. Combined stage sums are computed per observation before statistics.',
              'memoryScope': 'Exact texture/cloud primitive payload fields and native raster estimate do not count contour object storage. Optional retainedContourBasisBytes is reported separately; absent legacy values are treated as zero and reporting presence is recorded. geometryCacheEstimateBytes is an implementation accounting estimate, not a heap measurement; contour changes can alter it. Equal texture/cloud/native payloads do not establish unchanged total retention.',
              'cachePolicyScope': 'buildCachePolicy and per-case reuseContourBasis may intentionally differ between variants and are reported explicitly, while within-variant policy drift between rounds is rejected. Legacy absent reuseContourBasis means false. Timed repeated cases can therefore compare different internal cache policies without changing fixture, Tumble, oracle or sample order.',
              'timingScope': 'CPU geometry and native raster texture creation; not GPU execution or physical Android performance. Fixed-order rounds retained separately; no statistical significance or production recommendation inferred automatically.',
              'cases': cases}
    out.mkdir(parents=True, exist_ok=True)
    def write_csv(name, rows):
        with (out/name).open('w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    write_csv('summary.csv', phase_rows)
    write_csv('paired-allocation-deltas.csv', pair_rows)
    write_csv('reported-memory.csv', memory_rows)
    write_csv('build-cache-policy.csv', policy_rows)
    (out/'comparison.json').write_text(json.dumps(result, indent=2)+'\n')
    lines = [f'# JVM comparison: {args.before} → {args.after}', '',
             f"All {len(pair_rows)} paired samples match both exact F16 and contour/uniform checksums ({len(pair_rows)*2} checks). Required-equal payload fields: {', '.join(required_equal) or 'none'}. {len(rounds)} rounds, {reference['samples']} samples/case/variant/round, {reference['warmupsPerCase']} warmups plus {reference['initialWarmupsPerCase']} initialization passes.", '',
             '| Case | Round | Combined wall median/p95 ms before→after | Combined CPU median ms before→after | Allocated bytes median before→after |',
             '|---|---|---:|---:|---:|']
    def f(value, divisor=1, precision=3):
        return f'{value/divisor:.{precision}f}' if value is not None else 'unavailable'
    for case in cases:
        for phase in case['phases']['combined']:
            b, a = phase['before'], phase['after']
            lines.append(f"| {case['name']} | {phase['round']} | {f(b['wallNs']['median'],1e6)}/{f(b['wallNs']['p95'],1e6)}→{f(a['wallNs']['median'],1e6)}/{f(a['wallNs']['p95'],1e6)} | {f(b['cpuNs']['median'],1e6)}→{f(a['cpuNs']['median'],1e6)} | {f(b['allocatedBytes']['median'],precision=0)}→{f(a['allocatedBytes']['median'],precision=0)} |")
    lines += ['', 'Build-cache policy before: ' + (build_cache_policies[args.before]['buildCachePolicy'] or '(not reported by legacy harness)'),
              '', 'Build-cache policy after: ' + (build_cache_policies[args.after]['buildCachePolicy'] or '(not reported by legacy harness)'), '',
              '| Case | Reuse contour basis before→after | Retained contour basis bytes median before→after |', '|---|---|---:|']
    for case in cases:
        policy, memory = case['buildCachePolicy'], case['reportedMemory']['retainedContourBasisBytes']
        lines.append(f"| {case['name']} | {policy['before']['reuseContourBasis']}→{policy['after']['reuseContourBasis']} | {f(memory['before']['median'],precision=0)}→{f(memory['after']['median'],precision=0)} |")
    lines += ['', result['scope'], '', result['memoryScope'], '', result['cachePolicyScope'], '', result['timingScope'], '',
              'Every paired allocation delta is retained in `paired-allocation-deltas.csv`; CPU/stage medians and p95 ratios are in `summary.csv`. All fields, input hashes and per-case observed delta distributions are in `comparison.json`. No allocation-saving model or recommendation is embedded in this comparator.', '']
    (out/'findings.md').write_text('\n'.join(lines))
    print(json.dumps({'before': args.before, 'after': args.after, 'parity': result['parity'], 'output': str(out)}, indent=2))


if __name__ == '__main__':
    main()
