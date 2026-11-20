"""Small HTTP boundary shared by the Strong import's local tools."""

import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request


class ApiError(Exception):
    def __init__(self, status, code):
        super().__init__(f'HTTP {status}: {code}')
        self.status = status
        self.code = code


def server_origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        raise ValueError('Server must be an HTTPS origin without a path, query or credentials')
    return value.rstrip('/')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None  # Never forward credentials or mutation bodies to another URL.


def request_json(origin, path, *, body=None, token=None, key=None):
    headers = {'Accept': 'application/json'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    request = urllib.request.Request(server_origin(origin) + path, data=body, headers=headers,
                                     method='POST' if body is not None else 'GET')
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        code = 'request_failed'
        try:
            candidate = json.loads(error.read(16384)).get('errorCode', code)
            if isinstance(candidate, str) and candidate.replace('_', '').isalnum():
                code = candidate
        except (ValueError, AttributeError):
            pass
        raise ApiError(error.code, code) from None
    except urllib.error.URLError:
        raise ApiError(0, 'connection_failed') from None


def private_json(path, value):
    """Atomically replace a tool-owned private JSON file; never leave partial JSON."""
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
