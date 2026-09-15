"""Validate completed telemetry before the next mutation and retain immutable snapshots."""

import hashlib
import json
import math
import os
from pathlib import Path


class EvidenceGuard:
    FILES = ("provider-events.jsonl", "invocation-events.jsonl", "dispatch-journal.jsonl", "observations.jsonl")

    def __init__(self, root):
        self.root = Path(root)
        self.previous = {}
        self.number = 0

    def read(self):
        data = {name: (self.root / name).read_bytes() if (self.root / name).exists() else b"" for name in self.FILES}
        for name, previous in self.previous.items():
            if not data[name].startswith(previous):
                raise RuntimeError(f"Evidence integrity: previously checkpointed {name} changed or shrank")
        rows = {name: [json.loads(line) for line in raw.splitlines() if line.strip()] for name, raw in data.items()}
        events = rows["provider-events.jsonl"] + rows["invocation-events.jsonl"]
        sequences = sorted(row["sequence"] for row in events)
        if sequences != list(range(1, len(sequences) + 1)):
            raise RuntimeError("Evidence integrity: telemetry sequence has a gap or duplicate")
        return data, rows

    def verify_terminal(self, terminal):
        _, rows = self.read()
        iid = terminal["invocationId"]
        attempts = [r for r in rows["provider-events.jsonl"] if r["invocationId"] == iid]
        ids = [r["eventId"] for r in attempts]
        reserved = [r["attemptId"] for r in rows["dispatch-journal.jsonl"] if r["type"] == "reserve" and r["reservation"]["invocationId"] == iid]
        terminals = [r for r in rows["invocation-events.jsonl"] if r["invocationId"] == iid]
        reported = terminal.get("providerAttemptIds", [])
        if len(terminals) != 1 or len(set(ids)) != len(ids) or len(set(reserved)) != len(reserved) or len(set(reported)) != len(reported):
            raise RuntimeError("Evidence integrity: duplicate invocation or attempt identity")
        if set(ids) != set(reserved) or set(ids) != set(reported):
            raise RuntimeError("Evidence integrity: terminal, provider events and reservation identities disagree")
        if terminal.get("usageComplete"):
            if any(not r.get("usageAvailable") or r.get("costEur") is None for r in attempts):
                raise RuntimeError("Evidence integrity: terminal hides unavailable usage")
            total = sum(r["costEur"] for r in attempts)
            if terminal.get("costEur") is None or not math.isclose(total, terminal["costEur"], rel_tol=1e-9, abs_tol=1e-12):
                raise RuntimeError("Evidence integrity: terminal cost disagrees with provider attempts")

    def checkpoint(self):
        data, _ = self.read()
        if data == self.previous:
            return
        folder = self.root / "checkpoints" / f"{self.number:04d}"
        folder.mkdir(parents=True, exist_ok=False)
        hashes = {}
        for name, raw in data.items():
            with (folder / name).open("xb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            hashes[name] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        (folder / "sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
        self.previous = data
        self.number += 1
