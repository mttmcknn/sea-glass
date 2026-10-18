#!/usr/bin/env python3
"""Validate inspected coordinates and freeze local APK/helper identities. No device calls."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex

HERE = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--apk', action='append', default=[], metavar='VARIANT=PATH')
    parser.add_argument('--identity', action='append', default=[], metavar='VARIANT=TEXT',
                        help='Renderer commit, identical scene/diagnostic source hashes, build settings')
    parser.add_argument('--output', type=Path, default=HERE / 'prepared')
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if config.get('coordinates_verified') is not True:
        parser.error('Coordinates must be verified on the actual scene first')
    for key in ('dataset_id', 'device_id'):
        if not isinstance(config.get(key), str) or not config[key]:
            parser.error(f'Missing {key}')
    for key in ('viewport_width', 'viewport_height', 'density_dpi', 'swipe_x',
                'swipe_high_y', 'swipe_low_y', 'drag_count', 'drag_duration_ms',
                'fling_count', 'fling_duration_ms'):
        if type(config.get(key)) is not int or config[key] <= 0:
            parser.error(f'{key} must be a positive integer')
    if not (0 < config['swipe_x'] < config['viewport_width'] and
            0 < config['swipe_high_y'] < config['swipe_low_y'] < config['viewport_height']):
        parser.error('Coordinates outside viewport or incorrect high/low order')
    for key in ('drag_gap_seconds', 'fling_gap_seconds', 'minimum_settle_seconds'):
        if not isinstance(config.get(key), (int, float)) or config[key] < 0:
            parser.error(f'{key} must be nonnegative')
    gesture_seconds = (config['drag_count'] * (config['drag_duration_ms'] / 1000 + config['drag_gap_seconds']) +
                       config['fling_count'] * (config['fling_duration_ms'] / 1000 + config['fling_gap_seconds']))
    if gesture_seconds + config['minimum_settle_seconds'] > 7.5:
        parser.error('Gesture recipe leaves insufficient settling time in the eight-second trace')
    apks = {}
    for item in args.apk:
        variant, path_text = item.split('=', 1)
        if not variant or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in variant):
            parser.error(f'Unsafe variant label: {variant!r}')
        path = Path(path_text).resolve(strict=True)
        apks[variant] = {'path': str(path), 'bytes': path.stat().st_size, 'sha256': sha(path)}
    identities = dict(item.split('=', 1) for item in args.identity)
    for variant in apks:
        if variant not in identities:
            parser.error(f'Missing --identity for {variant}')
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / 'manifest.json'
    device_config = args.output / 'device-config.sh'
    for existing in (target, device_config):
        if existing.exists():
            parser.error(f'Refusing to overwrite prepared file: {existing}')
    shell_keys = ('dataset_id', 'viewport_width', 'viewport_height', 'density_dpi', 'swipe_x',
                  'swipe_high_y', 'swipe_low_y', 'drag_count', 'drag_duration_ms', 'drag_gap_seconds',
                  'fling_count', 'fling_duration_ms', 'fling_gap_seconds', 'minimum_settle_seconds')
    shell = '# Generated from inspected configuration; do not edit after freezing.\n'
    shell += '\n'.join(f'{key.upper()}={shlex.quote(str(config[key]))}' for key in shell_keys) + '\n'
    device_config.write_text(shell)
    helpers = ['capture-block.sh', 'capture-retention.sh', 'visual-checkpoints.sh', 'gesture.sh',
               'cold.pbtxt', 'scroll.pbtxt', 'idle.pbtxt']
    manifest = {'status': 'prepared-not-captured', 'config': config, 'apks': apks,
                'identities': identities, 'plannedOrder': ['B1', 'F1', 'F2', 'B2'],
                'helperSha256': {name: sha(HERE / name) for name in helpers},
                'deviceConfigSha256': sha(device_config),
                'gestureRecipeNominalSeconds': gesture_seconds,
                'limitations': ['Emulator evidence, not physical GPU/power/thermal validation.',
                                'Native heap/PSS gauges are not exact retained-texture accounting.']}
    target.write_text(json.dumps(manifest, indent=2) + '\n')
    print(target)


if __name__ == '__main__':
    main()
