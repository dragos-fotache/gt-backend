"""Importer recovery and verification regressions using synthetic API responses."""

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from strong_api import ApiError, private_json
from strong_apply import ImportSession, accepted_batch, assert_matches, load_plan
from strong_import import mutation, write_plan


class ApplyTest(unittest.TestCase):
    def test_duplicate_rejection_is_not_treated_as_a_success(self):
        m = mutation('fixture', 'workout', {'id': '12345678-1234-4234-8234-123456789abc'})
        response = {'results': [{'mutationId': m['mutationId'], 'status': 'duplicate', 'errorCode': 'invalid_reference'}]}
        with self.assertRaisesRegex(ValueError, 'invalid_reference'):
            accepted_batch(response, m)

    def test_published_batch_must_match_the_planned_workout(self):
        m = mutation('fixture', 'workout', {'id': '12345678-1234-4234-8234-123456789abc', 'name': 'Expected'})
        result = {'mutationId': m['mutationId'], 'status': 'accepted', 'batch': {
            'cursor': 'cursor', 'batchId': 'batch', 'changes': [{'aggregateType': 'workout',
                'aggregateId': m['aggregateId'], 'operation': 'upsert', 'revision': 1,
                'record': m['payload']['record']}]}}
        accepted_batch({'results': [result]}, m)
        damaged = copy.deepcopy(result)
        damaged['batch']['changes'][0]['record']['name'] = 'Different'
        with self.assertRaisesRegex(ValueError, 'Readback value mismatch'):
            accepted_batch({'results': [damaged]}, m)

    def test_modified_request_bytes_are_rejected_before_any_network_call(self):
        m = mutation('fixture', 'workout', {'id': '12345678-1234-4234-8234-123456789abc', 'status': 'finished'})
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory) / 'plan'
            write_plan(plan, [m], {})
            _, requests, _ = load_plan(plan)
            filename = requests[0][0]['file']
            with (plan / filename).open('ab') as output:
                output.write(b' ')
            with self.assertRaisesRegex(ValueError, 'modified'):
                load_plan(plan)

    def test_lost_refresh_response_recovers_original_key_and_body_after_restart(self):
        grant = {'account': {'id': '12345678-1234-4234-8234-123456789abc'}, 'accessToken': 'old-access',
                 'refreshToken': 'old-refresh', 'accessExpiresAt': '2020-01-01T00:00:00Z'}
        calls = []

        def lost(origin, path, **kwargs):
            calls.append((path, kwargs))
            raise ApiError(0, 'connection_failed')

        def recovered(origin, path, **kwargs):
            calls.append((path, kwargs))
            return {**grant, 'accessToken': 'new-access', 'refreshToken': 'new-refresh',
                    'accessExpiresAt': '2099-01-01T00:00:00Z'}

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'session.json'
            private_json(path, {'origin': 'https://example.test', 'grant': grant})
            first = ImportSession(path, lost)
            with self.assertRaises(ApiError):
                first.refresh()
            second = ImportSession(path, recovered)
            second.refresh()
            self.assertEqual(calls[0], calls[1])
            saved = json.loads(path.read_text())
            self.assertNotIn('pendingRefresh', saved)
            self.assertEqual('new-refresh', saved['grant']['refreshToken'])

    def test_readback_accepts_timestamp_spelling_but_detects_changed_set_values(self):
        expected = {'startedAt': '2026-03-28T08:00:00.000Z', 'sets': [{'weightKg': 20.0, 'repetitions': 8}]}
        actual = {'startedAt': '2026-03-28T08:00:00Z', 'sets': [{'weightKg': 20, 'repetitions': 8}], 'revision': 1}
        assert_matches(expected, actual)
        actual['sets'][0]['weightKg'] = 21
        with self.assertRaisesRegex(ValueError, 'weightKg'):
            assert_matches(expected, actual)


if __name__ == '__main__':
    unittest.main()
