import json
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from decimal import Decimal as D
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import campaign as c
import heterogeneous as h
import meter
from observe import native_noop
import seed

PRICING = json.loads(Path(__file__).with_name('pricing.json').read_text())
REQUEST = {'model': 'gpt-5.6-luna', 'reasoning': {'effort': 'high'}, 'input': 'test', 'store': False}
RESPONSE = {'id': 'resp_test', 'model': 'gpt-5.6-luna', 'service_tier': 'default', 'status': 'completed',
            'usage': {'input_tokens': 100, 'input_tokens_details': {'cached_tokens': 20}, 'output_tokens': 50,
                      'output_tokens_details': {'reasoning_tokens': 30}, 'total_tokens': 150}, 'output': []}


class BenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.m = meter.Meter(self.root, {'budgetEur': '1.00'}, PRICING, 'https://api.openai.com/v1')
        self.m.begin('r01')

    def reserve(self):
        return self.m.reserve(json.dumps(REQUEST).encode(), '/v1/responses')

    def test_decimal_categories_and_reasoning_included_once(self):
        self.assertEqual(meter.cost(RESPONSE, PRICING), (D(80)*D('.2')+D(20)*D('.02')+D(50)*D('1.2'))/D(1000000)/D('1.149'))
        for tokens, multiplier in [(272000, 1), (272001, 2)]:
            r = json.loads(json.dumps(RESPONSE))
            r['usage'].update(input_tokens=tokens, output_tokens=0, total_tokens=tokens,
                              input_tokens_details={'cached_tokens': 0, 'cache_write_tokens': tokens}, output_tokens_details={})
            self.assertEqual(meter.cost(r, PRICING), D(tokens)*D('.25')*multiplier/D(1000000)/D('1.149'))

    def test_missing_and_unsupported_usage_never_zero(self):
        for update in [{'usage': None}, {'model': 'other'}, {'service_tier': 'priority'},
                       {'usage': {'input_tokens': 1, 'output_tokens': 2, 'total_tokens': 3}}]:
            with self.assertRaises((ValueError, TypeError, KeyError)):
                meter.cost(RESPONSE | update, PRICING)

    def test_missing_retry_retains_reservation_and_budget_blocks(self):
        first = self.reserve()
        self.m.settle(first, 502, b'{}', {})
        self.assertIn(first, self.m.pending)
        with self.assertRaisesRegex(ValueError, 'ceiling'): self.reserve()
        self.m.end()
        with self.assertRaises(RuntimeError): self.m.begin('r02')
        recovered = meter.Meter(self.root, self.m.manifest, PRICING, 'https://api.openai.com/v1')
        self.assertEqual(recovered.pending, self.m.pending)

    def test_retry_costs_both_count(self):
        for _ in range(2):
            ident = self.reserve()
            self.m.settle(ident, 200, json.dumps(RESPONSE).encode(), {'x-request-id': 'id'})
        self.assertEqual(self.m.spent, 2*meter.cost(RESPONSE, PRICING))
        self.assertFalse(self.m.pending)

    def test_configuration_rejected_before_dispatch(self):
        for value in [REQUEST | {'model': 'other'}, REQUEST | {'reasoning': {'effort': 'medium'}}, REQUEST | {'stream': True},
                      REQUEST | {'tools': [{'type': 'web_search'}]}]:
            with self.assertRaises(ValueError): self.m.reserve(json.dumps(value).encode(), '/v1/responses')
        with self.assertRaises(ValueError): self.m.reserve(json.dumps(REQUEST).encode(), '/v1/chat/completions')
        self.assertFalse(self.m.ledger.exists())

    def test_wire_transparency_and_transport_attempt_accounting(self):
        received = []
        body = json.dumps(RESPONSE, indent=3).encode()
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                received.append((self.path, self.rfile.read(int(self.headers['Content-Length'])), self.headers['Authorization']))
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('x-request-id', 'test-provider')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        self.m.upstream = meter.urlsplit(f'http://127.0.0.1:{upstream.server_port}/v1')
        proxy = meter.serve(self.m, 0)
        try:
            raw = json.dumps(REQUEST, indent=2).encode()
            req = urllib.request.Request(f'http://127.0.0.1:{proxy.server_port}/v1/responses', raw,
                                         {'Authorization': 'Bearer test-only', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req) as response:
                self.assertEqual(response.read(), body)
                self.assertEqual(response.headers['x-request-id'], 'test-provider')
            self.assertEqual(received, [('/v1/responses', raw, 'Bearer test-only')])
            self.assertNotIn('test-only', self.m.ledger.read_text())
            self.assertEqual(self.m.spent, meter.cost(RESPONSE, PRICING))
        finally:
            proxy.shutdown(); proxy.server_close()
            upstream.shutdown(); upstream.server_close()

    def test_design_and_bootstrap_partitions(self):
        fixture = seed.fixture()
        rows = h.schedule(fixture, c.CONDITIONS, 'formal', 20260910, 10)
        self.assertEqual(len(rows), 90)
        self.assertEqual(len({r['courseKey'] for r in rows}), 60)
        bootstrap = [r for r in rows if r['condition'] == 'bootstrap' and r['repetition'] == 1]
        self.assertEqual([len(r['changedObjectKeys']) for r in bootstrap], [12, 24, 24, 12])
        self.assertEqual(len(set(sum((r['changedObjectKeys'] for r in bootstrap), []))), 72)
        self.assertEqual(len(h.schedule(fixture, c.CONDITIONS, 'smoke', 20260910, 10)), 3)

    def test_report_criterion_boundaries_unknowns_and_failures(self):
        pricing = json.loads(json.dumps(PRICING))
        pricing['usdPerEur'] = '1'
        pricing['models']['gpt-5.6-luna']['outputUsdPerMillion'] = '1000000'
        p = self.root/'pricing.json'
        c.write(p, pricing)
        manifest = {'campaignId': 'test', 'sourceSha256': 'source', 'pricingSha256': meter.sha(p.read_bytes()),
                    'criterionEur': '15', 'phaseConfiguration': {'flavor-strip': {'model': 'gpt-5.6-luna', 'reasoningEffort': 'high', 'apiRoute': '/v1/responses'}},
                    'observations': [{'observationId': 'r01'}, {'observationId': 'r02'}]}
        c.write(self.root/'manifest.json', manifest)
        c.write(self.root/'outcomes.json', {'r01': {'status': 'failed-or-partial', 'evidenceComplete': True}, 'r02': {'status': 'completed', 'evidenceComplete': True}})
        for tokens, expected in [(14, 'pass'), (15, 'pass'), (16, 'fail')]:
            r = json.loads(json.dumps(RESPONSE))
            r['usage'].update(input_tokens=0, input_tokens_details={'cached_tokens': 0}, output_tokens=tokens, total_tokens=tokens, output_tokens_details={'reasoning_tokens': 0})
            self.m.ledger.write_text('')
            meter.append(self.m.ledger, {'event': 'reserved', 'id': 'a', 'observationId': 'r01', 'reservationEur': '20',
                                       'phase': 'flavor-strip', 'requestedModel': 'gpt-5.6-luna', 'reasoningEffort': 'high', 'apiRoute': '/v1/responses'})
            meter.append(self.m.ledger, r | {'event': 'settled', 'id': 'a', 'costEur': str(tokens)})
            with patch.object(c, 'fingerprint', return_value='source'):
                report = c.summarize(self.root)
                self.assertEqual(report['criterion'], expected)
                self.assertEqual(len(report['observations']), 2)
                self.assertEqual(report['observations'][0]['status'], 'failed-or-partial')
            with patch.object(c, 'fingerprint', return_value='changed'):
                self.assertEqual(c.summarize(self.root)['criterion'], 'fail' if tokens > 15 else 'incomplete')
        self.m.ledger.write_text(json.dumps({'event': 'reserved', 'id': 'a', 'observationId': 'r01', 'reservationEur': '1',
                                           'phase': 'flavor-strip', 'requestedModel': 'gpt-5.6-luna', 'reasoningEffort': 'high', 'apiRoute': '/v1/responses'})+'\n')
        with patch.object(c, 'fingerprint', return_value='source'):
            self.assertEqual(c.summarize(self.root)['criterion'], 'incomplete')

    def test_manifest_freezes_separate_criterion_and_ceiling(self):
        root = self.root/'formal'
        with patch.object(c, 'fingerprint', return_value='source'):
            c.prepare(root, 'formal', 'http://127.0.0.1:8083', 'https://api.openai.com/v1')
            manifest = c.verify(root)
        self.assertEqual((manifest['criterionEur'], manifest['budgetEur']), ('15.00', '20.00'))
        self.assertEqual(len(manifest['observations']), 90)
        self.assertEqual(manifest['phaseConfiguration']['flavor-strip']['reasoningEffort'], 'high')
        self.assertEqual(manifest['phaseConfiguration']['flavor-strip']['apiRoute'], '/v1/responses')
        with self.assertRaises(FileExistsError): c.prepare(root, 'formal', '', '')

    def test_native_noop_requires_matching_terminal_run_and_batch(self):
        start = 'INFO atlas.automatic course 42 firing run run-a with 8 exercise(s) and 4 lecture unit(s)'
        end = 'DEBUG atlas.automatic course 42 run run-a had no applicable changes; no summary broadcast'
        self.assertIsNone(native_noop([start], 42))
        self.assertIsNone(native_noop([end], 42))
        self.assertIsNone(native_noop([start, end.replace('run-a', 'run-b')], 42))
        self.assertIsNone(native_noop([start, end], 43))
        result = native_noop([start, end], 42)
        self.assertEqual((result['status'], result['exerciseCount'], result['runId']), ('NO_OP', 12, 'run-a'))
        with self.assertRaisesRegex(RuntimeError, 'Multiple automatic runs'):
            native_noop([start, start.replace('run-a', 'run-b'), end], 42)

    def test_changed_fingerprint_and_reseed_refused(self):
        c.write(self.root/'manifest.json', {'sourceSha256': 'old', 'fixtureSha256': seed.digest(seed.fixture())})
        with patch.object(c, 'fingerprint', return_value='new'):
            with self.assertRaisesRegex(RuntimeError, 'changed'): c.verify(self.root)
        c.write(self.root/'seed-started.json', {'at': 'previous'})
        with self.assertRaisesRegex(RuntimeError, 'do not replay'): c.seed_courses(self.root, {})


if __name__ == '__main__': unittest.main()
