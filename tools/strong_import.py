#!/usr/bin/env python3
"""Prepare Strong CSV history for Gymtracka's existing aggregate.import API.

Uses only the Python standard library. No server or database is accessed.
Personal CSVs, mappings, reports and generated request bytes belong outside Git.
Run with --help for the required, explicitly reviewed conversion policy.
"""

import argparse
import collections
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo


NAMESPACE = uuid.UUID('b3d8da70-2bac-4533-a1d0-50f1d6b3ac16')
FIELDS = {
    'Workout #', 'Date', 'Workout Name', 'Duration (sec)', 'Exercise Name',
    'Set Order', 'Weight (kg)', 'Reps', 'RPE', 'Distance (meters)', 'Seconds',
    'Notes', 'Workout Notes',
}


def integer(value, field):
    try:
        n = Decimal(value)
        if not n.is_finite() or n < 0 or n != n.to_integral_value() or n > 2147483647:
            raise ValueError()
        return int(n)
    except (InvalidOperation, ValueError):
        raise ValueError(f'Invalid nonnegative integer for {field}: {value!r}') from None


def weight(value):
    try:
        n = float(value)
    except ValueError:
        raise ValueError(f'Invalid weight: {value!r}') from None
    if not math.isfinite(n) or n < 0:
        raise ValueError(f'Invalid weight: {value!r}')
    return n


def read_source(path):
    raw = Path(path).read_bytes()
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig'), newline=''), delimiter=';')
    if reader.fieldnames is None or set(reader.fieldnames) != FIELDS or len(reader.fieldnames) != len(FIELDS):
        raise ValueError('Unexpected CSV header; review the format before importing')
    rows = []
    for row in reader:
        if None in row or any(v is None for v in row.values()):
            raise ValueError(f'Malformed CSV record ending at line {reader.line_num}')
        rows.append(row)
    if not rows:
        raise ValueError('Empty history')
    return hashlib.sha256(raw).hexdigest(), rows


def grouped_workouts(rows):
    groups = collections.OrderedDict()
    for row in rows:
        if not row['Workout #'] or not row['Exercise Name']:
            raise ValueError('Missing workout number or exercise name')
        groups.setdefault(row['Workout #'], []).append(row)
    for number, group in groups.items():
        for field in ('Date', 'Workout Name', 'Duration (sec)', 'Workout Notes'):
            if len({r[field] for r in group}) != 1:
                raise ValueError(f'Workout {number}: inconsistent {field}')
    return groups


def exercise_blocks(rows):
    blocks = []
    for row in rows:
        if not blocks or blocks[-1][0]['Exercise Name'] != row['Exercise Name']:
            blocks.append([])
        blocks[-1].append(row)
    return blocks


def inventory(source_hash, rows):
    groups = grouped_workouts(rows)
    exercises = collections.defaultdict(list)
    for row in rows:
        exercises[row['Exercise Name']].append(row)
    return {
        'sourceSha256': source_hash,
        'rowCount': len(rows), 'workoutCount': len(groups),
        'dateRange': [min(r['Date'] for r in rows), max(r['Date'] for r in rows)],
        'rowTypes': dict(collections.Counter(
            'set' if r['Set Order'].isdigit() else r['Set Order'] for r in rows)),
        'exercises': [{
            'strongName': name,
            'workoutCount': len({r['Workout #'] for r in group}),
            'setCount': sum(r['Set Order'].isdigit() for r in group),
        } for name, group in sorted(exercises.items())],
    }


def instant(local_date, zone):
    naive = datetime.strptime(local_date, '%Y-%m-%d %H:%M:%S')
    candidates = set()
    for fold in (0, 1):
        candidate = naive.replace(tzinfo=zone, fold=fold).astimezone(timezone.utc)
        if candidate.astimezone(zone).replace(tzinfo=None) == naive:
            candidates.add(candidate)
    if len(candidates) != 1:
        raise ValueError(f'Ambiguous or nonexistent local time: {local_date}')
    return candidates.pop()


def utc(value):
    return value.isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def identity(source_id, key):
    return str(uuid.uuid5(NAMESPACE, json.dumps([source_id, key], ensure_ascii=False)))


def mutation(source_id, kind, record):
    return {
        'mutationId': identity(source_id, ['mutation', kind, record['id']]),
        'type': 'aggregate.import', 'aggregateType': kind,
        'aggregateId': record['id'], 'localGeneration': 1,
        'payload': {'record': record},
    }


def prepare(source_hash, rows, config, catalogue):
    """Fail closed on unsupported fields, unresolved mappings and ambiguous dates.

    Rest Timer rows and strength-set Seconds are treated as configured timers,
    never measured rest. The final configured value becomes the exercise default;
    original per-set values are preserved in the separate report. restSeconds is
    left null. Weights are copied verbatim; no equipment multiplier is inferred.
    """
    if config.get('reviewed') is not True:
        raise ValueError('Exercise mapping and conversion policy have not been reviewed')
    if config.get('sourceSha256') != source_hash:
        raise ValueError('CSV differs from the reviewed source')
    source_id = config['sourceId']
    if not isinstance(source_id, str) or not source_id.strip():
        raise ValueError('sourceId must be a stable, nonempty identifier')
    if config.get('cardioPolicy') != 'report-only' or config.get('restPolicy') != 'configured-only':
        raise ValueError('Review cardioPolicy/report-only and restPolicy/configured-only')
    zone = ZoneInfo(config['timezone'])
    default_rest = integer(str(config['defaultRestSeconds']), 'default rest')
    mappings = config['exercises']
    if set(mappings) != {r['Exercise Name'] for r in rows}:
        raise ValueError('Exercise mappings must exactly cover the source exercise names')
    catalogue_by_id = {e['id']: e for e in catalogue}
    custom_by_id = {}
    for name, mapping in mappings.items():
        if mapping.get('reviewed') is not True:
            raise ValueError(f'Unreviewed exercise mapping: {name}')
        if mapping.get('mode') == 'report-only':
            continue
        exercise_id = mapping['exerciseId']
        if str(uuid.UUID(exercise_id)) != exercise_id:
            raise ValueError(f'Invalid exercise ID for {name}')
        custom = mapping.get('customExercise')
        if custom:
            if custom['id'] != exercise_id or exercise_id in catalogue_by_id:
                raise ValueError(f'Invalid custom exercise identity for {name}')
            if exercise_id in custom_by_id and custom_by_id[exercise_id] != custom:
                raise ValueError(f'Conflicting custom exercise definitions for {name}')
            custom_by_id[exercise_id] = custom
        elif exercise_id not in catalogue_by_id:
            raise ValueError(f'Exercise absent from pinned catalogue: {name}')
        elif catalogue_by_id[exercise_id]['name'] != mapping['gymtrackaName']:
            raise ValueError(f'Catalogue ID/name mismatch: {name}')

    report = inventory(source_hash, rows)
    report.update({'timezone': config['timezone'], 'excludedRows': [], 'configuredRest': [],
                   'workouts': [], 'customExercises': list(custom_by_id.values())})
    mutations = [mutation(source_id, 'customExercise', e) for e in custom_by_id.values()]
    for number, group in grouped_workouts(rows).items():
        first = group[0]
        if first['Workout Notes']:
            raise ValueError(f'Workout {number}: workout-level notes require an explicit policy')
        start = instant(first['Date'], zone)
        duration = integer(first['Duration (sec)'], 'workout duration')
        root_id = identity(source_id, ['workout', number, first['Date']])
        exercises = []
        for block_index, block in enumerate(exercise_blocks(group)):
            name = block[0]['Exercise Name']
            mapping = mappings[name]
            if mapping.get('mode') == 'report-only':
                report['excludedRows'].extend(block)
                continue
            entry_id = identity(source_id, ['workout-exercise', root_id, block_index])
            sets, notes, timers = [], [], []
            for row in block:
                if row['RPE'] or row['Distance (meters)']:
                    raise ValueError(f'Workout {number}/{name}: unsupported RPE or distance')
                if row['Notes'].strip():
                    notes.append(row['Notes'])
                order = row['Set Order']
                if order == 'Note':
                    if any(row[k] for k in ('Weight (kg)', 'Reps', 'Seconds')):
                        raise ValueError(f'Workout {number}/{name}: unexpected numeric note data')
                    continue
                if order == 'Rest Timer':
                    if not sets or row['Weight (kg)'] or row['Reps']:
                        raise ValueError(f'Workout {number}/{name}: orphan or malformed rest timer')
                    seconds = integer(row['Seconds'], 'configured timer')
                    timers.append(seconds)
                    report['configuredRest'].append({'workoutNumber': number, 'strongName': name,
                        'blockIndex': block_index, 'setPosition': len(sets) - 1,
                        'sourceRowType': order, 'seconds': seconds})
                    continue
                if not order.isdigit() or int(order) != len(sets) + 1:
                    raise ValueError(f'Workout {number}/{name}: unsupported or nonconsecutive set order {order!r}')
                sets.append({
                    'id': identity(source_id, ['workout-set', entry_id, order]),
                    'position': len(sets), 'repetitions': integer(row['Reps'], 'repetitions'),
                    'weightKg': weight(row['Weight (kg)']), 'isCompleted': True, 'restSeconds': None,
                })
                if row['Seconds']:
                    seconds = integer(row['Seconds'], 'strength-set configured timer')
                    timers.append(seconds)
                    report['configuredRest'].append({'workoutNumber': number, 'strongName': name,
                        'blockIndex': block_index, 'setPosition': len(sets) - 1,
                        'sourceRowType': 'set', 'seconds': seconds})
            if not sets:
                raise ValueError(f'Workout {number}/{name}: no completed strength sets')
            exercises.append({
                'id': entry_id, 'exerciseId': mapping['exerciseId'], 'position': len(exercises),
                'notes': '\n'.join(notes),
                'restDurationSeconds': timers[-1] if timers else default_rest, 'sets': sets,
            })
        if not exercises:
            raise ValueError(f'Workout {number}: no importable strength exercises')
        record = {'id': root_id, 'name': first['Workout Name'], 'status': 'finished',
                  'startedAt': utc(start), 'finishedAt': utc(start + timedelta(seconds=duration)),
                  'exercises': exercises}
        mutations.append(mutation(source_id, 'workout', record))
        report['workouts'].append({'strongNumber': number, 'id': root_id, 'name': record['name'],
            'startedAt': record['startedAt'], 'finishedAt': record['finishedAt'],
            'exerciseCount': len(exercises), 'setCount': sum(len(e['sets']) for e in exercises),
            'volumeKg': sum(s['weightKg'] * s['repetitions'] for e in exercises for s in e['sets'])})
    report['importedSetCount'] = sum(w['setCount'] for w in report['workouts'])
    report['importedExerciseEntryCount'] = sum(w['exerciseCount'] for w in report['workouts'])
    return mutations, report


def write_private(path, data):
    with open(path, 'xb') as f:
        os.chmod(path, 0o600)
        f.write(data)


def write_plan(output, mutations, report):
    # Never overwrite a prior plan: these exact bytes are its retry identity.
    output = Path(output)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = []
    for index, m in enumerate(mutations):
        body = json.dumps({'mutations': [m]}, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
        name = f'{index:03d}-{m["aggregateType"]}-{m["aggregateId"]}.json'
        write_private(output / name, body)
        manifest.append({'file': name, 'sha256': hashlib.sha256(body).hexdigest(),
                         'mutationId': m['mutationId'], 'aggregateId': m['aggregateId'],
                         'aggregateType': m['aggregateType']})
    report['requests'] = manifest
    write_private(output / 'report.json', json.dumps(report, ensure_ascii=False, indent=2).encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--config', type=Path, help='Reviewed private mapping/policy JSON; omit for inventory only')
    parser.add_argument('--catalogue', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'shared-docs/catalogue/v1/en/exercises.json')
    parser.add_argument('--output', type=Path, help='New private directory for exact API requests and report')
    args = parser.parse_args()
    source_hash, rows = read_source(args.csv)
    if not args.config:
        print(json.dumps(inventory(source_hash, rows), ensure_ascii=False, indent=2))
        return
    if not args.output:
        parser.error('--output is required with --config')
    mutations, report = prepare(source_hash, rows, json.loads(args.config.read_text()),
                                json.loads(args.catalogue.read_text()))
    write_plan(args.output, mutations, report)
    print(f'Prepared {len(report["workouts"])} workouts, {report["importedSetCount"]} strength sets, '
          f'{len(report["customExercises"])} custom exercises; no server writes.')
    print(f'Report and immutable request bytes: {args.output}')


if __name__ == '__main__':
    main()
