"""External, non-streaming Responses meter. Request/response bodies pass unchanged.

Each upstream attempt is durably reserved before dispatch. Only complete supported
usage replaces a reservation; timeouts, errors, and unknown usage retain it.
"""
import hashlib
import http.client
import json
import os
import threading
import uuid
from datetime import datetime, timezone
from decimal import Decimal as D
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def append(path, row):
    with open(path, 'a', encoding='utf-8') as out:
        out.write(json.dumps(row, separators=(',', ':')) + '\n')
        out.flush()
        os.fsync(out.fileno())


def cost(response, pricing):
    """Return Decimal EUR, or reject incomplete/unsupported evidence (never zero)."""
    if not isinstance(response, dict):
        raise ValueError('Response must be an object')
    if response.get('model') not in pricing['models']:
        raise ValueError('Unpriced response model')
    if response.get('service_tier') not in (None, 'default'):
        raise ValueError('Unpriced service tier')
    usage = response['usage']
    if not isinstance(usage, dict):
        raise ValueError('Usage is missing')
    def count(mapping, key, default=None):
        value = mapping.get(key, default)
        if type(value) is not int or value < 0:
            raise ValueError('Missing/invalid token count: ' + key)
        return value
    i, o = count(usage, 'input_tokens'), count(usage, 'output_tokens')
    details = usage.get('input_tokens_details') or {}
    if not isinstance(details, dict): raise ValueError('Malformed input details')
    c = count(details, 'cached_tokens')
    w = count(details, 'cache_write_tokens', 0)
    if c + w > i or count(usage, 'total_tokens') != i + o:
        raise ValueError('Inconsistent usage totals')
    for key, value in details.items():
        if key not in {'cached_tokens', 'cache_write_tokens'} and value not in (None, 0):
            raise ValueError('Unsupported input category: ' + key)
    output_details = usage.get('output_tokens_details') or {}
    if not isinstance(output_details, dict): raise ValueError('Malformed output details')
    if count(output_details, 'reasoning_tokens', 0) > o: raise ValueError('Reasoning exceeds output total')
    for key, value in output_details.items():
        if key != 'reasoning_tokens' and value not in (None, 0):
            raise ValueError('Unsupported output category: ' + key)
    r = pricing['models'][response['model']]
    if i > r['maxSupportedInputTokens'] or o > r['maxOutputTokens']:
        raise ValueError('Usage exceeds priced bounds')
    lc = r['longContext']
    im = D(str(lc['inputMultiplier'])) if i > lc['thresholdTokens'] else D(1)
    om = D(str(lc['outputMultiplier'])) if i > lc['thresholdTokens'] else D(1)
    return ((i-c-w)*D(r['inputUsdPerMillion'])*im + c*D(r['cachedInputUsdPerMillion'])*im
            + w*D(r['cacheWriteInputUsdPerMillion'])*im + o*D(r['outputUsdPerMillion'])*om) / D(1000000) / D(pricing['usdPerEur'])


def reservation(pricing, model):
    r = pricing['models'][model]
    return (D(r['maxSupportedInputTokens'])*max(D(r[k]) for k in ('inputUsdPerMillion', 'cachedInputUsdPerMillion', 'cacheWriteInputUsdPerMillion'))
            * D(str(r['longContext']['inputMultiplier'])) + D(r['maxOutputTokens'])*D(r['outputUsdPerMillion'])
            * D(str(r['longContext']['outputMultiplier']))) / D(1000000) / D(pricing['usdPerEur'])


def phase(request):
    # These three call shapes are observable on the wire in the frozen Atlas code.
    if not isinstance(request, dict) or not isinstance(request.get('reasoning'), dict):
        raise ValueError('Malformed request configuration')
    if request.get('model') != 'gpt-5.6-luna' or request.get('stream') or request.get('background'):
        raise ValueError('Unexpected model or asynchronous transport')
    if request.get('service_tier') not in (None, 'auto', 'default'):
        raise ValueError('Unpriced requested tier')
    if any(tool.get('type') != 'function' for tool in request.get('tools', [])):
        raise ValueError('Paid built-in tool is not covered by text pricing')
    effort = (request.get('reasoning') or {}).get('effort')
    if effort == 'xhigh' and request.get('tools'):
        return 'orchestration'
    if effort == 'high':
        return 'worker' if request.get('tools') else 'flavor-strip'
    raise ValueError('Unexpected reasoning effort')


class Meter:
    def __init__(self, root, manifest, pricing, upstream):
        self.root, self.manifest, self.pricing = Path(root), manifest, pricing
        self.upstream = urlsplit(upstream.rstrip('/'))
        if self.upstream.scheme not in {'https', 'http'} or self.upstream.query or self.upstream.fragment:
            raise ValueError('Invalid upstream URL')
        if self.upstream.scheme != 'https' and self.upstream.hostname not in {'localhost', '127.0.0.1'}:
            raise ValueError('Unencrypted upstream must be local')
        self.lock, self.active, self.inflight, self.spent = threading.RLock(), None, set(), D(0)
        self.pending, self.blocked = {}, False
        self.ledger = self.root / 'provider-attempts.jsonl'
        if self.ledger.exists():
            # Reconstruct liabilities, including interrupted attempts. No automatic write replay.
            for row in map(json.loads, self.ledger.read_text().splitlines()):
                if row['event'] == 'reserved': self.pending[row['id']] = D(row['reservationEur'])
                if row['event'] == 'settled':
                    self.pending.pop(row['id'])
                    self.spent += D(row['costEur'])
                if row['event'] == 'blocked': self.blocked = True

    def begin(self, observation):
        with self.lock:
            if self.active or self.inflight or self.pending or self.blocked:
                raise RuntimeError('Previous observation/attempt is unresolved')
            self.active = observation

    def end(self):
        with self.lock:
            if self.inflight:
                raise RuntimeError('Provider attempt still running')
            self.active = None

    def reserve(self, raw, path):
        with self.lock:
            if not self.active or path != '/v1/responses' or self.blocked:
                raise ValueError('No active observation or unexpected API route')
            request = json.loads(raw)
            category = phase(request)
            amount = reservation(self.pricing, request['model'])
            if self.spent + sum(self.pending.values(), D(0)) + amount > D(self.manifest['budgetEur']):
                raise ValueError('Campaign spending ceiling reached')
            ident = uuid.uuid4().hex
            row = dict(event='reserved', id=ident, at=now(), observationId=self.active,
                       reservationEur=str(amount), apiRoute=path, phase=category,
                       requestedModel=request['model'], reasoningEffort=request['reasoning']['effort'], requestSha256=sha(raw))
            append(self.ledger, row)
            self.pending[ident] = amount
            self.inflight.add(ident)
            return ident

    def settle(self, ident, status, raw, headers):
        with self.lock:
            row = dict(event='unknown', id=ident, at=now(), httpStatus=status,
                       responseSha256=sha(raw), providerRequestId=headers.get('x-request-id'))
            try:
                if status != 200: raise ValueError('Non-success response; retain reservation')
                response = json.loads(raw)
                amount = cost(response, self.pricing)
                if amount > self.pending[ident]: raise ValueError('Cost exceeds reservation')
                row.update(event='settled', costEur=str(amount), usage=response['usage'], model=response['model'],
                           responseId=response.get('id'), responseStatus=response.get('status'),
                           tools=[{'name': x.get('name'), 'callId': x.get('call_id')} for x in response.get('output', []) if x.get('type') == 'function_call'])
            except (ValueError, KeyError, TypeError) as error:
                row['reason'] = str(error)
            append(self.ledger, row)
            if row['event'] == 'settled':
                self.spent += D(row['costEur'])
                self.pending.pop(ident)
            self.inflight.discard(ident)

    def reject(self, reason):
        with self.lock:
            self.blocked = True
            append(self.ledger, dict(event='blocked', at=now(), observationId=self.active, reason=reason))


def serve(meter, port):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass  # Never log credentials or prompt bodies.
        def respond(self, status, raw, headers=None):
            self.send_response(status)
            for key, value in (headers or {}).items():
                if key.lower() not in {'connection', 'transfer-encoding', 'content-length', 'server', 'date'}:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            try:
                ident = meter.reserve(raw, self.path)
            except (ValueError, RuntimeError, KeyError, TypeError) as error:
                meter.reject(str(error))
                self.respond(400, json.dumps({'error': {'message': str(error)}}).encode())
                return
            upstream = meter.upstream
            cls = http.client.HTTPSConnection if upstream.scheme == 'https' else http.client.HTTPConnection
            conn = cls(upstream.hostname, upstream.port, timeout=1800)
            try:
                headers = {k: v for k, v in self.headers.items() if k.lower() not in {'host', 'connection', 'content-length', 'transfer-encoding', 'accept-encoding'}}
                headers['Accept-Encoding'] = 'identity'
                conn.request('POST', upstream.path + '/responses', raw, headers)
                response = conn.getresponse()
                body = response.read()
                response_headers = dict(response.getheaders())
                meter.settle(ident, response.status, body, {k.lower(): v for k, v in response_headers.items()})
                self.respond(response.status, body, response_headers)
            except (OSError, http.client.HTTPException):
                # SDK may retry; that is a distinct recorded upstream attempt.
                if ident in meter.inflight:
                    meter.settle(ident, 0, b'', {})
                try: self.respond(502, b'{"error":{"message":"Upstream transport failed; cost unknown"}}')
                except OSError: pass
            finally:
                conn.close()
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server
