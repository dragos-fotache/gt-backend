#!/usr/bin/env python3
"""One-use localhost Google sign-in for an independent Gymtracka import session.

Uses the existing backend challenge/Google exchange endpoints. The Google OAuth
web client must allow http://localhost and http://localhost:8765 (or chosen port).
Credentials are never logged or sent back to the browser after exchange.
"""

import argparse
import json
from pathlib import Path
import re
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

from strong_api import ApiError, private_json, request_json, server_origin


HTML = b'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gymtracka import sign-in</title>
<style>body{font:17px/1.6 system-ui;margin:10vh auto;padding:24px;max-width:580px;color:#18332b;background:#f4f7f4}
main{padding:32px;background:white;border:1px solid #d4e1d7;border-radius:16px}h1{font-size:27px}#status{white-space:pre-wrap}button{font:inherit;padding:8px 18px}</style>
<main><h1>Sign in to Gymtracka</h1>
<p>Use the same Google account as your Gymtracka app. This creates a separate session for importing your Strong history.</p>
<p id="server"></p><div id="google-button"></div><p id="status">Loading Google sign-in...</p>
<button id="retry" hidden>Retry connection</button></main>
<script>
let settings, pendingCredential;
const status = document.getElementById('status');
async function exchange() {
  document.getElementById('retry').hidden = true;
  status.textContent = 'Signing in to Gymtracka...';
  try {
    const response = await fetch('exchange', {method:'POST', headers:{'Content-Type':'application/json',
      'X-Import-Session':settings.csrf}, body:JSON.stringify({credential:pendingCredential})});
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || result.errorCode);
    pendingCredential = undefined;
    document.getElementById('google-button').replaceChildren();
    status.textContent = 'Signed in' + (result.displayName ? ' as ' + result.displayName : '') +
      '. You can close this tab and return to the import conversation.';
  } catch (error) {
    status.textContent = 'Sign-in could not finish: ' + error.message;
    document.getElementById('retry').hidden = false;
  }
}
document.getElementById('retry').onclick = exchange;
async function initialize() {
  try {
    const response = await fetch('settings');
    settings = await response.json();
    if (!response.ok) throw new Error(settings.message || settings.errorCode);
    document.getElementById('server').textContent = 'Server: ' + settings.server;
    google.accounts.id.initialize({client_id:settings.clientId,nonce:settings.nonce,
      auto_select:false,callback:result=>{pendingCredential=result.credential;exchange();}});
    google.accounts.id.renderButton(document.getElementById('google-button'),{theme:'outline',size:'large'});
    status.textContent = 'Choose your Gymtracka Google account.';
  } catch (error) {status.textContent = 'Unable to start sign-in: ' + error.message;}
}
</script><script src="https://accounts.google.com/gsi/client" async defer onload="initialize()"
onerror="document.getElementById('status').textContent='Could not load Google sign-in. Check your connection.'"></script></html>'''


class SignInState:
    def __init__(self, origin, client_id, session_file, transport=request_json):
        self.origin = server_origin(origin)
        self.client_id = client_id
        self.session_file = Path(session_file)
        if self.session_file.exists():
            raise ValueError('Session output already exists; use the saved session or a new output path')
        self.transport = transport
        self.csrf = secrets.token_urlsafe(32)
        self.path = '/' + secrets.token_urlsafe(24) + '/'
        self.challenge = None
        self.challenge_created = 0
        self.key = str(uuid.uuid4())
        self.exchange_body = None
        self.complete = False

    def settings(self):
        if self.complete:
            raise ValueError('Sign-in already completed')
        if self.challenge is None or (self.exchange_body is None and time.monotonic() - self.challenge_created >= 240):
            self.challenge = self.transport(self.origin, '/v1/auth/google/challenge', body=b'{}')
            self.challenge_created = time.monotonic()
        return {'server': self.origin, 'clientId': self.client_id,
                'nonce': self.challenge['nonce'], 'csrf': self.csrf}

    def exchange(self, credential):
        if self.complete or self.challenge is None or not isinstance(credential, str) or not credential:
            raise ValueError('No active sign-in challenge')
        body = json.dumps({'challengeId': self.challenge['challengeId'], 'idToken': credential},
                          separators=(',', ':')).encode()
        if self.exchange_body is not None and self.exchange_body != body:
            raise ValueError('A different sign-in attempt cannot replace an uncertain exchange; restart this helper')
        self.exchange_body = body  # Every network retry uses the original body and key.
        grant = self.transport(self.origin, '/v1/auth/google', body=body, key=self.key)
        uuid.UUID(grant['account']['id'])
        for key in ('accessToken', 'refreshToken', 'accessExpiresAt', 'refreshExpiresAt'):
            if not isinstance(grant.get(key), str) or not grant[key]:
                raise ValueError('Incomplete service session received')
        private_json(self.session_file, {'origin': self.origin, 'grant': grant})
        self.complete = True
        self.exchange_body = None
        return {'accountId': grant['account']['id'], 'displayName': grant['account'].get('displayName')}


def handler_for(state, port):
    expected_host = f'localhost:{port}'
    expected_origin = f'http://{expected_host}'

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never log request URLs, bodies or credentials.

        def reply(self, status, body, content_type='application/json'):
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer-when-downgrade')
            self.send_header('Cross-Origin-Opener-Policy', 'same-origin-allow-popups')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('X-Frame-Options', 'DENY')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.headers.get('Host') != expected_host:
                self.reply(403, {'errorCode': 'invalid_host'})
            elif self.path == state.path:
                self.reply(200, HTML, 'text/html; charset=utf-8')
            elif self.path == state.path + 'settings':
                try:
                    self.reply(200, state.settings())
                except (ApiError, ValueError) as error:
                    self.reply(502, {'message': str(error)})
            else:
                self.reply(404, {'errorCode': 'not_found'})

        def do_POST(self):
            if (self.headers.get('Host') != expected_host or self.headers.get('Origin') != expected_origin
                    or not secrets.compare_digest(self.headers.get('X-Import-Session', ''), state.csrf)):
                self.reply(403, {'errorCode': 'invalid_origin_or_session'})
                return
            if self.path != state.path + 'exchange':
                self.reply(404, {'errorCode': 'not_found'})
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 32768:
                    raise ValueError('Invalid request size')
                request = json.loads(self.rfile.read(length))
                result = state.exchange(request['credential'])
                self.reply(200, result)
                print('Signed in to Gymtracka account ' + result['accountId'], flush=True)
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            except (ApiError, ValueError, KeyError) as error:
                # Only sanitized transport errors and fixed validation messages reach the page.
                message = str(error) if isinstance(error, ApiError) else 'Invalid sign-in response; retry or restart the helper'
                self.reply(502, {'message': message})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True)
    parser.add_argument('--client-id', required=True)
    parser.add_argument('--session-file', type=Path, required=True)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--page-token', help='Reuse an existing local page URL when restarting before sign-in')
    args = parser.parse_args()
    if not re.fullmatch(r'[\w.-]+\.apps\.googleusercontent\.com', args.client_id):
        parser.error('Expected the configured Google web/server OAuth client ID')
    args.session_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    state = SignInState(args.server, args.client_id, args.session_file)
    if args.page_token:
        if not re.fullmatch(r'[A-Za-z0-9_-]{24,64}', args.page_token):
            parser.error('Invalid local page token')
        state.path = '/' + args.page_token + '/'
    with HTTPServer(('127.0.0.1', args.port), handler_for(state, args.port)) as server:
        server.timeout = 30
        print(f'Open http://localhost:{args.port}{state.path}', flush=True)
        print('This helper signs in only; it does not import workouts.', flush=True)
        server.serve_forever()
    print('Import session saved privately. The sign-in helper has stopped.', flush=True)


if __name__ == '__main__':
    main()
