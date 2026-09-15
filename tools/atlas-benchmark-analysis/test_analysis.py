"""Evidence failures must be explicit, and published figures must use complete data."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('analysis',HERE/'analyze.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
EVIDENCE=HERE.parents[1]/'benchmark-results/atlas-logos-heterogeneous-formal-fresh-20260914-v1'

class AnalysisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pricing=json.loads((EVIDENCE/'pricing.json').read_text())
        cls.event=json.loads((EVIDENCE/'provider-events.jsonl').read_text().splitlines()[0])
        cls.gateway={x['requestId']:x for x in map(json.loads,(EVIDENCE/'logos.jsonl').read_text().splitlines())}

    def test_complete_archive_without_network(self):
        with patch('socket.socket.connect',side_effect=AssertionError('Network use forbidden')):
            rows,summary,audit=a.analyze(EVIDENCE)
        self.assertEqual((len(rows),summary['providerAttempts'],summary['criterion']),(90,2639,'pass'))
        self.assertLess(abs(summary['totalCostEur']-a.D('1.744587351744437711020690849')),a.D('1e-25'))
        self.assertEqual(summary['maximumObservationId'],'r01-bootstrap-s3')
        self.assertEqual(summary['bootstrapStages'],4)
        self.assertFalse(audit['failedChecks'])

    def test_missing_usage_is_not_zero(self):
        event=dict(self.event,usageAvailable=False)
        with self.assertRaisesRegex(a.AuditError,'Missing or invalid usage'):
            a.attempt_cost(event,self.pricing,self.gateway)

    def test_unsupported_model_is_rejected(self):
        event=dict(self.event,returnedModel='unverified-model')
        with self.assertRaisesRegex(a.AuditError,'Unsupported model'):
            a.attempt_cost(event,self.pricing,self.gateway)

    def test_unsupported_frozen_price_is_rejected(self):
        import copy
        pricing=copy.deepcopy(self.pricing)
        pricing['models'][self.event['returnedModel']]['inputUsdPerMillion']='unverified'
        with self.assertRaisesRegex(a.AuditError,'Unsupported frozen pricing'):
            a.attempt_cost(self.event,pricing,self.gateway)

    def test_negative_frozen_price_is_rejected(self):
        import copy
        pricing=copy.deepcopy(self.pricing)
        pricing['models'][self.event['returnedModel']]['inputUsdPerMillion']='-0.20'
        with self.assertRaisesRegex(a.AuditError,'Invalid frozen price'):
            a.attempt_cost(self.event,pricing,self.gateway)

    def test_cache_bounds_are_checked(self):
        event=dict(self.event,cachedInputTokens=self.event['inputTokens']+1)
        with self.assertRaisesRegex(a.AuditError,'outside supported pricing'):
            a.attempt_cost(event,self.pricing,self.gateway)

    def test_missing_gateway_usage_is_not_zero(self):
        import copy
        gateway=copy.deepcopy(self.gateway)
        del gateway[self.event['logosRequestId']]['usage']['prompt_tokens']
        with self.assertRaisesRegex(a.AuditError,'Gateway usage mismatch'):
            a.attempt_cost(self.event,self.pricing,gateway)

    def test_corrupted_package_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'data').write_text('before')
            a.dump(root/'checksums.json',{'data':a.sha(root/'data')})
            (root/'data').write_text('after')
            with self.assertRaisesRegex(a.AuditError,'checksum mismatch'):a.verify_package(root)

    def test_duplicate_attempt_identity_is_rejected_even_with_updated_checksums(self):
        # A trusted-hash check alone cannot establish uniqueness of supplied records.
        import shutil
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ['manifest.json','pricing.json','schedule.json','fixtures.json','provider-events.jsonl','logos.jsonl','invocation-events.jsonl','observations.jsonl','dispatch-journal.jsonl','report.json','fixture-definition.json','source-sha256.json']:
                shutil.copyfile(EVIDENCE/name,root/name)
            (root/'source').symlink_to(EVIDENCE/'source',target_is_directory=True)
            with (root/'provider-events.jsonl').open('a') as f:f.write(json.dumps(self.event)+'\n')
            with patch.object(a,'verify_package',return_value={}):
                with self.assertRaisesRegex(a.AuditError,'unique gateway and attempt identities'):a.analyze(root)

if __name__=='__main__':unittest.main()
