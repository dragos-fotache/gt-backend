"""Sign-in safety and exact retry behavior without contacting Google or a server."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from strong_api import ApiError
from strong_sign_in import SignInState, handler_for


class SignInTest(unittest.TestCase):
    def test_uncertain_exchange_retries_exact_key_body_and_nonce_and_saves_privately(self):
        calls = []
        fail_once = [True]
        grant = {'account': {'id': '12345678-1234-4234-8234-123456789abc'},
                 'accessToken': 'test-access', 'refreshToken': 'test-refresh',
                 'accessExpiresAt': '2026-10-18T18:00:00Z', 'refreshExpiresAt': '2026-11-18T18:00:00Z'}

        def transport(origin, path, **kwargs):
            calls.append((path, kwargs))
            if path.endswith('/challenge'):
                return {'nonce': 'test-nonce', 'challengeId': 'test-challenge'}
            if fail_once.pop() if fail_once else False:
                raise ApiError(0, 'connection_failed')
            return grant

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'session.json'
            state = SignInState('https://example.test', 'test-client', output, transport)
            self.assertEqual('test-nonce', state.settings()['nonce'])
            self.assertEqual('test-nonce', state.settings()['nonce'])
            with self.assertRaises(ApiError):
                state.exchange('test-google-token')
            self.assertFalse(output.exists())
            result = state.exchange('test-google-token')
            self.assertEqual(calls[1], calls[2])
            self.assertEqual(3, len(calls))
            self.assertNotIn('accessToken', result)
            self.assertNotIn('refreshToken', result)
            self.assertEqual({'origin': 'https://example.test', 'grant': grant}, json.loads(output.read_text()))
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertIsNone(state.exchange_body)

    def test_different_google_credential_cannot_replace_uncertain_attempt(self):
        def transport(origin, path, **kwargs):
            if path.endswith('/challenge'):
                return {'nonce': 'test-nonce', 'challengeId': 'test-challenge'}
            raise ApiError(0, 'connection_failed')

        with tempfile.TemporaryDirectory() as directory:
            state = SignInState('https://example.test', 'test-client', Path(directory) / 'session.json', transport)
            state.settings()
            with self.assertRaises(ApiError):
                state.exchange('first')
            with self.assertRaisesRegex(ValueError, 'different sign-in attempt'):
                state.exchange('different')

    def test_foreign_origin_is_rejected_before_reading_a_google_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            state = SignInState('https://example.test', 'test-client', Path(directory) / 'session.json')
            handler_class = handler_for(state, 8765)
            request = handler_class.__new__(handler_class)
            request.headers = {'Host': 'localhost:8765', 'Origin': 'https://other.test',
                               'X-Import-Session': state.csrf}
            replies = []
            request.reply = lambda *args: replies.append(args)
            request.do_POST()
            self.assertEqual(403, replies[0][0])

    def test_existing_session_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'session.json'
            output.write_text('original')
            with self.assertRaisesRegex(ValueError, 'already exists'):
                SignInState('https://example.test', 'test-client', output)
            self.assertEqual('original', output.read_text())


if __name__ == '__main__':
    unittest.main()
