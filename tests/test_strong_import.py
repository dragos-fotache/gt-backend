"""Offline regression checks for the Strong conversion boundary; no personal data."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zoneinfo import ZoneInfo


spec = importlib.util.spec_from_file_location('strong_import', Path(__file__).resolve().parents[1] / 'tools/strong_import.py')
strong = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strong)

EXERCISE_ID = 'd586b5aa-c2f4-4cb5-8038-d10b03c3b763'
CATALOGUE = [{'id': EXERCISE_ID, 'name': 'Barbell Bench Press'}]


def row(**changes):
    value = dict.fromkeys(strong.FIELDS, '')
    value.update({'Workout #': '1', 'Date': '2026-03-28 10:00:00', 'Workout Name': 'Example',
                  'Duration (sec)': '3600', 'Exercise Name': 'Bench Press (Barbell)',
                  'Set Order': '1', 'Weight (kg)': '20.0', 'Reps': '8'})
    value.update(changes)
    return value


def config():
    return {'reviewed': True, 'sourceSha256': 'fixture', 'sourceId': 'test-only',
            'timezone': 'Europe/Bucharest', 'cardioPolicy': 'report-only',
            'restPolicy': 'configured-only', 'defaultRestSeconds': 30,
            'exercises': {'Bench Press (Barbell)': {
                'reviewed': True, 'exerciseId': EXERCISE_ID, 'gymtrackaName': 'Barbell Bench Press'}}}


class StrongImportTest(unittest.TestCase):
    def convert(self, rows, policy=None):
        return strong.prepare('fixture', rows, policy or config(), CATALOGUE)

    def test_note_and_timer_rows_do_not_become_sets_or_measured_rest(self):
        rows = [row(**{'Set Order': 'Note', 'Weight (kg)': '', 'Reps': '', 'Notes': 'Original note'}),
                row(), row(**{'Set Order': 'Rest Timer', 'Weight (kg)': '', 'Reps': '', 'Seconds': '90.0'}),
                row(**{'Set Order': '2', 'Weight (kg)': '0.0', 'Seconds': '120.0'})]
        mutations, report = self.convert(rows)
        record = mutations[0]['payload']['record']
        self.assertEqual('2026-03-28T08:00:00.000Z', record['startedAt'])
        self.assertEqual('2026-03-28T09:00:00.000Z', record['finishedAt'])
        exercise = record['exercises'][0]
        self.assertEqual('Original note', exercise['notes'])
        self.assertEqual(120, exercise['restDurationSeconds'])
        self.assertEqual([20.0, 0.0], [s['weightKg'] for s in exercise['sets']])
        self.assertTrue(all(s['isCompleted'] and s['restSeconds'] is None for s in exercise['sets']))
        self.assertEqual([90, 120], [t['seconds'] for t in report['configuredRest']])
        self.assertEqual(2, report['importedSetCount'])

    def test_timezone_uses_historical_dst_and_duration_is_elapsed_time(self):
        rows = [row(**{'Date': '2026-03-29 02:30:00', 'Duration (sec)': '7200'})]
        mutations, _ = self.convert(rows)
        record = mutations[0]['payload']['record']
        self.assertEqual('2026-03-29T00:30:00.000Z', record['startedAt'])
        self.assertEqual('2026-03-29T02:30:00.000Z', record['finishedAt'])
        with self.assertRaisesRegex(ValueError, 'Ambiguous or nonexistent'):
            strong.instant('2026-03-29 03:30:00', ZoneInfo('Europe/Bucharest'))
        with self.assertRaisesRegex(ValueError, 'Ambiguous or nonexistent'):
            strong.instant('2026-10-25 03:30:00', ZoneInfo('Europe/Bucharest'))

    def test_cardio_is_preserved_only_in_report_and_positions_are_compacted(self):
        cardio = row(**{'Exercise Name': 'Treadmill', 'Weight (kg)': '', 'Reps': '',
                        'Distance (meters)': '1250.0', 'Seconds': '600.0'})
        policy = config()
        policy['exercises']['Treadmill'] = {'reviewed': True, 'mode': 'report-only'}
        mutations, report = self.convert([cardio, row()], policy)
        exercises = mutations[0]['payload']['record']['exercises']
        self.assertEqual(1, len(exercises))
        self.assertEqual(0, exercises[0]['position'])
        self.assertEqual([cardio], report['excludedRows'])

    def test_repeated_target_references_remain_distinct_ordered_entries(self):
        policy = config()
        policy['exercises']['Alias'] = copy.deepcopy(policy['exercises']['Bench Press (Barbell)'])
        mutations, _ = self.convert([row(), row(**{'Exercise Name': 'Alias'})], policy)
        exercises = mutations[0]['payload']['record']['exercises']
        self.assertEqual([0, 1], [e['position'] for e in exercises])
        self.assertEqual(exercises[0]['exerciseId'], exercises[1]['exerciseId'])
        self.assertNotEqual(exercises[0]['id'], exercises[1]['id'])
        self.assertNotEqual(exercises[0]['sets'][0]['id'], exercises[1]['sets'][0]['id'])

    def test_custom_dependencies_precede_workouts_and_aliases_create_once(self):
        policy = config()
        custom = {'id': '12345678-1234-4234-8234-123456789abc', 'name': 'Test Custom'}
        for name in ['Bench Press (Barbell)', 'Alias']:
            policy['exercises'][name] = {'reviewed': True, 'exerciseId': custom['id'],
                                         'gymtrackaName': custom['name'], 'customExercise': custom}
        mutations, _ = self.convert([row(), row(**{'Exercise Name': 'Alias'})], policy)
        self.assertEqual(['customExercise', 'workout'], [m['aggregateType'] for m in mutations])

    def test_refuses_unreviewed_or_changed_input(self):
        for field, value in [('reviewed', False), ('sourceSha256', 'other')]:
            policy = config()
            policy[field] = value
            with self.assertRaises(ValueError):
                self.convert([row()], policy)
        policy = config()
        policy['exercises']['Bench Press (Barbell)']['reviewed'] = False
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            self.convert([row()], policy)

    def test_refuses_silent_data_loss_or_fabricated_inputs(self):
        for changes in [{'Weight (kg)': ''}, {'Reps': ''}, {'Weight (kg)': '-1'},
                        {'Weight (kg)': 'NaN'}, {'Reps': '1.5'}, {'Set Order': 'W'},
                        {'Set Order': '2'}, {'RPE': '8'}, {'Workout Notes': 'Must preserve'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.convert([row(**changes)])
        with self.assertRaisesRegex(ValueError, 'orphan'):
            self.convert([row(**{'Set Order': 'Rest Timer', 'Weight (kg)': '', 'Reps': '', 'Seconds': '60'})])

    def test_inconsistent_workout_metadata_rejects(self):
        with self.assertRaisesRegex(ValueError, 'inconsistent'):
            self.convert([row(), row(**{'Set Order': '2', 'Workout Name': 'Different'})])

    def test_exact_bytes_and_all_identities_are_repeatable_and_not_overwritten(self):
        first, report = self.convert([row()])
        second, _ = self.convert([row()])
        self.assertEqual(first, second)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'plan'
            strong.write_plan(directory, first, report)
            saved = json.loads((directory / 'report.json').read_text())
            request = (directory / saved['requests'][0]['file']).read_bytes()
            self.assertEqual(first, json.loads(request)['mutations'])
            self.assertEqual(saved['requests'][0]['sha256'], strong.hashlib.sha256(request).hexdigest())
            self.assertEqual(0o600, (directory / 'report.json').stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                strong.write_plan(directory, second, report)


if __name__ == '__main__':
    unittest.main()
