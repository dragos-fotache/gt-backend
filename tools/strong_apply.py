#!/usr/bin/env python3
"""Inspect, apply and verify an immutable Strong import plan through the API.

Requires a separate session produced by strong_sign_in.py. Defaults to read-only
inspection. Progress is bound to the server, account and exact report bytes.
An interrupted mutation retries its original bytes and UUID, never a new identity.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.parse
import uuid

from strong_api import ApiError, private_json, request_json, server_origin


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Expected an offset-aware server timestamp')
    return result


def assert_matches(expected, actual, path='record'):
    """Compare write fields, tolerating only server-added fields and timestamp spelling."""
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            raise ValueError(f'Readback shape mismatch: {path}')
        for key, value in expected.items():
            if key not in actual:
                raise ValueError(f'Readback missing field: {path}.{key}')
            if key in ('startedAt', 'finishedAt') and value is not None:
                if timestamp(value) != timestamp(actual[key]):
                    raise ValueError(f'Readback timestamp mismatch: {path}.{key}')
            else:
                assert_matches(value, actual[key], path + '.' + key)
    elif isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            raise ValueError(f'Readback list mismatch: {path}')
        for i, (left, right) in enumerate(zip(expected, actual)):
            assert_matches(left, right, f'{path}[{i}]')
    elif expected != actual:
        raise ValueError(f'Readback value mismatch: {path}')


def expected_resource(mutation):
    record = mutation['payload']['record']
    if mutation['aggregateType'] == 'workout':
        return record
    # Custom-exercise reads project reference IDs as resolved catalogue objects.
    result = {k: v for k, v in record.items() if k in (
        'id', 'name', 'category', 'difficultyLevel', 'forceType', 'mechanics', 'instructions')}
    result['isUserCreated'] = True
    muscles = []
    if record.get('primaryMuscleGroupId'):
        muscles.append({'id': record['primaryMuscleGroupId'], 'type': 'primary'})
    muscles.extend({'id': value, 'type': 'secondary'} for value in record.get('secondaryMuscleGroupIds', []))
    # The read adapter sorts references by ID.
    result['muscleGroups'] = sorted(muscles, key=lambda value: value['id'])
    result['equipment'] = ([{'id': record['equipmentId'], 'numItems': record.get('equipmentNumItems')}]
                           if record.get('equipmentId') else [])
    return result


def accepted_batch(response, mutation):
    results = response.get('results', [])
    if len(results) != 1 or results[0].get('mutationId') != mutation['mutationId']:
        raise ValueError('Unexpected mutation response identity')
    result = results[0]
    if result.get('status') not in ('accepted', 'duplicate') or result.get('errorCode'):
        raise ValueError('Import rejected: ' + str(result.get('errorCode', result.get('status'))))
    batch = result.get('batch')
    if not isinstance(batch, dict) or not batch.get('cursor') or not batch.get('batchId'):
        raise ValueError('Accepted import has no published sync batch')
    changes = batch.get('changes', [])
    if len(changes) != 1:
        raise ValueError('Finished-history import must publish exactly one change')
    change = changes[0]
    if (change.get('aggregateId') != mutation['aggregateId'] or change.get('aggregateType') != mutation['aggregateType']
            or change.get('operation') != 'upsert' or change.get('revision', 0) < 1):
        raise ValueError('Unexpected import change or revision')
    assert_matches(expected_resource(mutation), change.get('record'))
    return result


class ImportSession:
    def __init__(self, path, transport=request_json):
        self.path = Path(path)
        self.data = json.loads(self.path.read_text())
        self.origin = server_origin(self.data['origin'])
        self.account_id = self.data['grant']['account']['id']
        uuid.UUID(self.account_id)
        self.transport = transport

    def refresh(self):
        pending = self.data.get('pendingRefresh')
        if pending is None:
            pending = {'key': str(uuid.uuid4()), 'body': json.dumps(
                {'refreshToken': self.data['grant']['refreshToken']}, separators=(',', ':'))}
            self.data['pendingRefresh'] = pending
            private_json(self.path, self.data)
        grant = self.transport(self.origin, '/v1/auth/refresh',
                               body=pending['body'].encode(), key=pending['key'])
        if grant['account']['id'] != self.account_id:
            raise ValueError('Refresh changed account identity')
        self.data['grant'] = grant
        del self.data['pendingRefresh']
        private_json(self.path, self.data)

    def call(self, path, body=None):
        if (self.data.get('pendingRefresh') or timestamp(self.data['grant']['accessExpiresAt'])
                <= datetime.now(timezone.utc) + timedelta(seconds=60)):
            self.refresh()
        refreshed = False
        for attempt in range(4):
            try:
                return self.transport(self.origin, path, body=body, token=self.data['grant']['accessToken'])
            except ApiError as error:
                if error.status == 401 and not refreshed:
                    self.refresh()
                    refreshed = True
                elif error.status in (0, 429, 500, 502, 503, 504) and attempt < 3:
                    time.sleep(attempt + 1)
                else:
                    raise
        raise ValueError('API retry limit reached; rerun using the saved plan and session')

    def pages(self, path):
        items, seen = [], set()
        while True:
            page = self.call(path)
            items.extend(page['items'])
            if not page['hasMore']:
                return items
            token = page['nextPageToken']
            if not token or token in seen:
                raise ValueError('Invalid browse continuation')
            seen.add(token)
            path = path.split('&pageToken=', 1)[0] + '&pageToken=' + urllib.parse.quote(token, safe='')


def load_plan(path):
    path = Path(path)
    raw = (path / 'report.json').read_bytes()
    report = json.loads(raw)
    requests, identifiers = [], set()
    for entry in report['requests']:
        if Path(entry['file']).name != entry['file']:
            raise ValueError('Request file must be inside the plan directory')
        body = (path / entry['file']).read_bytes()
        if hashlib.sha256(body).hexdigest() != entry['sha256']:
            raise ValueError('A saved request was modified; original retry bytes are required')
        mutations = json.loads(body)['mutations']
        if len(mutations) != 1:
            raise ValueError('Expected exactly one immutable mutation per request')
        mutation = mutations[0]
        if (mutation['type'] != 'aggregate.import' or mutation['aggregateType'] not in ('workout', 'customExercise')
                or any(mutation[k] != entry[k] for k in ('mutationId', 'aggregateId', 'aggregateType'))
                or mutation['aggregateId'] != mutation['payload']['record']['id']):
            raise ValueError('Request manifest identity or import type mismatch')
        if mutation['mutationId'] in identifiers:
            raise ValueError('Duplicate mutation identity')
        identifiers.add(mutation['mutationId'])
        if mutation['aggregateType'] == 'workout' and mutation['payload']['record']['status'] != 'finished':
            raise ValueError('This tool only imports finished history')
        requests.append((entry, body, mutation))
    return report, requests, hashlib.sha256(raw).hexdigest()


def resource_path(mutation):
    return ('/v1/workouts/' if mutation['aggregateType'] == 'workout' else '/v1/exercises/') + mutation['aggregateId']


def verify_sync(session, results):
    expected = {item['outcome']['batch']['batchId']: item['outcome']['batch'] for item in results.values()}
    found, cursors = set(), set()
    path = '/v1/sync/pull?limit=200'
    while True:
        page = session.call(path)
        for batch in page['batches']:
            batch_id = batch['batchId']
            if batch_id in expected:
                if batch != expected[batch_id]:
                    raise ValueError('Incremental pull differs from the committed import batch')
                found.add(batch_id)
        if not page['hasMore']:
            break
        cursor = page['nextCursor']
        if not cursor or cursor in cursors:
            raise ValueError('Invalid incremental sync continuation')
        cursors.add(cursor)
        path = '/v1/sync/pull?limit=200&cursor=' + urllib.parse.quote(cursor, safe='')
    if found != set(expected):
        raise ValueError(f'Incremental pull is missing {len(set(expected) - found)} import batches')
    return len(found)


def inspect(session, requests):
    existing = session.pages('/v1/workouts?limit=200')
    planned = {m['aggregateId'] for _, _, m in requests}
    history = [m['payload']['record'] for _, _, m in requests if m['aggregateType'] == 'workout']
    overlaps = []
    for existing_workout in existing:
        if existing_workout['id'] in planned:
            continue
        for incoming in history:
            if abs((timestamp(existing_workout['startedAt']) - timestamp(incoming['startedAt'])).total_seconds()) < 600:
                overlaps.append({'existing': existing_workout, 'incomingId': incoming['id']})
    custom_ids = {m['aggregateId'] for _, _, m in requests if m['aggregateType'] == 'customExercise'}
    referenced = {e['exerciseId'] for item in history for e in item['exercises']} - custom_ids
    for exercise_id in sorted(referenced):
        session.call('/v1/exercises/' + exercise_id)
    for _, _, mutation in requests:
        if mutation['aggregateType'] == 'customExercise':
            matches = session.pages('/v1/catalogue/exercises?limit=200&q=' + urllib.parse.quote(mutation['payload']['record']['name']))
            if any(e['name'].casefold() == mutation['payload']['record']['name'].casefold()
                   and e['id'] != mutation['aggregateId'] for e in matches):
                raise ValueError('A custom exercise with this name already exists; review the mapping')
    return {'accountId': session.account_id, 'origin': session.origin,
            'existingWorkouts': existing, 'overlapCandidates': overlaps,
            'validatedCatalogueReferences': len(referenced)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['inspect', 'apply', 'verify', 'logout'], nargs='?', default='inspect')
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--session-file', type=Path, required=True)
    parser.add_argument('--max-workouts', type=int, help='Import only this many planned workouts for a pilot')
    args = parser.parse_args()
    if args.max_workouts is not None and args.max_workouts < 1:
        parser.error('--max-workouts must be positive')
    session = ImportSession(args.session_file)
    report, requests, digest = load_plan(args.plan)
    state_path = args.plan / 'progress.json'
    state = {'origin': session.origin, 'accountId': session.account_id, 'reportSha256': digest, 'results': {}}
    if state_path.exists():
        saved = json.loads(state_path.read_text())
        if any(saved[k] != state[k] for k in ('origin', 'accountId', 'reportSha256')):
            raise ValueError('Progress belongs to another server, account or plan')
        state = saved
    if args.command == 'logout':
        # This is the independent helper session, never the Android app's session.
        body = json.dumps({'refreshToken': session.data['grant']['refreshToken']}).encode()
        session.transport(session.origin, '/v1/auth/logout', body=body)
        private_json(args.session_file, {'origin': session.origin, 'accountId': session.account_id, 'revoked': True})
        print('The separate import session has been revoked; local credentials removed.')
        return
    if args.command in ('inspect', 'apply'):
        before = inspect(session, requests)
        private_json(args.plan / 'inspection.json', before)
        print(f'Account {session.account_id}: {len(before["existingWorkouts"])} existing workouts; '
              f'{len(before["overlapCandidates"])} overlap candidates; '
              f'{before["validatedCatalogueReferences"]} catalogue references validated.', flush=True)
        if before['overlapCandidates']:
            raise ValueError('Possible duplicate workouts require review before import')
        if args.command == 'inspect':
            return
        if not (args.plan / 'baseline.json').exists():
            private_json(args.plan / 'baseline.json', before)
    workouts, sets = 0, 0
    for entry, body, mutation in requests:
        is_workout = mutation['aggregateType'] == 'workout'
        if is_workout and args.max_workouts is not None and workouts >= args.max_workouts:
            break
        if args.command == 'apply' and mutation['mutationId'] not in state['results']:
            response = session.call('/v1/sync/push', body)
            result = accepted_batch(response, mutation)
            state['results'][mutation['mutationId']] = {'outcome': result, 'verified': False}
            private_json(state_path, state)
        resource = session.call(resource_path(mutation))
        assert_matches(expected_resource(mutation), resource)
        if mutation['mutationId'] in state['results']:
            state['results'][mutation['mutationId']]['verified'] = True
            private_json(state_path, state)
        if is_workout:
            workouts += 1
            sets += sum(len(e['sets']) for e in mutation['payload']['record']['exercises'])
            print(f'Verified workout {workouts}: {mutation["payload"]["record"]["name"]}', flush=True)
    print(f'Verified {workouts} workouts and {sets} sets through independent resource reads.', flush=True)
    if args.max_workouts is None and len(state['results']) == len(requests):
        batches = verify_sync(session, state['results'])
        after = session.pages('/v1/workouts?limit=200')
        after_ids = {item['id'] for item in after}
        imported_ids = {m['aggregateId'] for _, _, m in requests if m['aggregateType'] == 'workout'}
        baseline_path = args.plan / 'baseline.json'
        baseline_ids = ({item['id'] for item in json.loads(baseline_path.read_text())['existingWorkouts']}
                        if baseline_path.exists() else set())
        if not imported_ids.issubset(after_ids) or not baseline_ids.issubset(after_ids):
            raise ValueError('Final workout list is missing imported or pre-existing workouts')
        state.update({'syncBatchesVerified': batches, 'totalWorkoutsAfterImport': len(after),
                      'completedAt': datetime.now(timezone.utc).isoformat()})
        private_json(state_path, state)
        print(f'Incremental pull verified all {batches} import batches; '
              f'{len(after)} total workouts, with all pre-existing workout IDs retained.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ApiError, ValueError, KeyError) as error:
        print('Stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
