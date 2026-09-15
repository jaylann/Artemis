"""Offline regressions for the observed missing-tail and misleading-terminal incident."""

import json
import tempfile
import unittest
from pathlib import Path

from integrity import EvidenceGuard


class IntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.provider = {"sequence": 1, "invocationId": "i1", "eventId": "a1", "usageAvailable": True, "costEur": 0.01}
        self.terminal = {"sequence": 2, "invocationId": "i1", "providerAttemptIds": ["a1"], "usageComplete": True, "costEur": 0.01}
        self.write("provider-events.jsonl", [self.provider])
        self.write("invocation-events.jsonl", [self.terminal])
        self.write("dispatch-journal.jsonl", [{"type": "reserve", "attemptId": "a1", "reservation": {"invocationId": "i1"}}])

    def write(self, name, rows):
        (self.root / name).write_text("".join(json.dumps(r) + "\n" for r in rows))

    def test_valid_terminal_is_checkpointed_without_altering_source(self):
        original = (self.root / "provider-events.jsonl").read_bytes()
        guard = EvidenceGuard(self.root)
        guard.verify_terminal(self.terminal)
        guard.checkpoint()
        self.assertEqual(original, (self.root / "checkpoints/0000/provider-events.jsonl").read_bytes())
        self.assertEqual(original, (self.root / "provider-events.jsonl").read_bytes())

    def test_lost_provider_tail_and_replacement_terminal_are_rejected(self):
        self.write("provider-events.jsonl", [])
        replacement = dict(self.terminal, sequence=3, providerAttemptIds=[], costEur=0)
        self.write("invocation-events.jsonl", [replacement])
        with self.assertRaisesRegex(RuntimeError, "gap"):
            EvidenceGuard(self.root).verify_terminal(replacement)

    def test_terminal_cannot_forget_known_requests_even_without_sequence_gap(self):
        replacement = dict(self.terminal, providerAttemptIds=[], costEur=0)
        self.write("invocation-events.jsonl", [replacement])
        with self.assertRaisesRegex(RuntimeError, "identities disagree"):
            EvidenceGuard(self.root).verify_terminal(replacement)

    def test_uncaptured_reservation_blocks_next_observation(self):
        self.write("dispatch-journal.jsonl", [{"type": "reserve", "attemptId": i, "reservation": {"invocationId": "i1"}} for i in ["a1", "a2"]])
        with self.assertRaisesRegex(RuntimeError, "identities disagree"):
            EvidenceGuard(self.root).verify_terminal(self.terminal)

    def test_rewriting_checkpointed_content_is_rejected(self):
        guard = EvidenceGuard(self.root)
        guard.checkpoint()
        self.write("provider-events.jsonl", [dict(self.provider, costEur=0.02)])
        with self.assertRaisesRegex(RuntimeError, "changed or shrank"):
            guard.checkpoint()

    def test_usage_and_cost_disagreements_are_rejected(self):
        for change in [{"usageAvailable": False}, {"costEur": 0.02}]:
            with self.subTest(change=change):
                self.write("provider-events.jsonl", [dict(self.provider, **change)])
                with self.assertRaisesRegex(RuntimeError, "usage|cost"):
                    EvidenceGuard(self.root).verify_terminal(self.terminal)

    def test_unknown_usage_remains_a_recorded_failure(self):
        self.write("provider-events.jsonl", [dict(self.provider, usageAvailable=False, costEur=None)])
        terminal = dict(self.terminal, usageComplete=False, costEur=None)
        self.write("invocation-events.jsonl", [terminal])
        EvidenceGuard(self.root).verify_terminal(terminal)


if __name__ == "__main__":
    unittest.main()
