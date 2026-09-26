#!/usr/bin/env python3
"""Resume a formal campaign after an infrastructure interruption, without replaying measured work.

Operator tool kept outside the tracked source so the frozen manifest fingerprint stays valid.
It enforces every precondition of campaign.execute() and reuses its per-observation procedure
verbatim, with two differences:

- observations whose outcome is already ``completed`` are kept and never dispatched again;
- an ``invalid-or-unmeasured`` observation is retried only when it provably left no trace:
  zero provider attempts and zero journaled API writes between its start and finish.
  Its earlier record is preserved under ``priorAttempts``.

``outcomes.json`` is snapshotted before any change.

A retained reservation does not block later observations only when its observation completed and the
provider answered that attempt with a definitive HTTP error (``unknown`` event with a status and a
provider request id), for example a transient 503 that the SDK retried successfully. Such a hold stays
in the ledger as that observation's conservative upper bound, is recorded in
``resume-carried-reservations.json``, and is subtracted from the remaining campaign ceiling. Transport
failures, blocks, and holds of failed observations still stop the campaign.
"""
import json
import shutil
import subprocess
import sys
import time
from datetime import datetime
from decimal import Decimal as D
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'supporting_scripts/benchmark'))
import campaign as c  # noqa: E402
import heterogeneous as h  # noqa: E402
import seed  # noqa: E402
from meter import Meter, serve, now, sha  # noqa: E402
from observe import Completion  # noqa: E402


def untouched(root, oid, record):
    attempts = [json.loads(line) for line in (root / 'provider-attempts.jsonl').read_text().splitlines()]
    if any(e.get('observationId') == oid for e in attempts):
        return False
    start, end = datetime.fromisoformat(record['startedAt']), datetime.fromisoformat(record['finishedAt'])
    writes = [json.loads(line) for line in (root / 'observation-writes.jsonl').read_text().splitlines()]
    return not any(start <= datetime.fromisoformat(w['at']) <= end for w in writes)


def carry(meter, root, outcomes, label, superseded=frozenset()):
    events = [json.loads(line) for line in (root / 'provider-attempts.jsonl').read_text().splitlines()]
    owner = {e['id']: e['observationId'] for e in events if e['event'] == 'reserved'}
    definitive = {e['id']: e for e in events if e['event'] == 'unknown' and e.get('httpStatus') and e.get('providerRequestId')}
    last = {e['id']: e for e in events if e['event'] == 'unknown'}
    record_path = root / 'resume-carried-reservations.json'
    carried = c.load(record_path) if record_path.exists() else {}
    for ident in list(meter.pending):
        oid = owner.get(ident)
        if ident in superseded or ident in definitive and outcomes.get(oid, {}).get('status') == 'completed':
            amount = meter.pending.pop(ident)
            meter.manifest['budgetEur'] = str(D(meter.manifest['budgetEur']) - amount)
            evidence = last.get(ident, {})
            carried[ident] = {'observationId': oid, 'reservationEur': str(amount), 'httpStatus': evidence.get('httpStatus'),
                              'providerRequestId': evidence.get('providerRequestId'), 'superseded': ident in superseded,
                              'carriedAt': now(), 'carriedBy': label}
            c.write(record_path, carried)


def preconditions(root, manifest, smoke):
    # Same gates as campaign.execute() for formal dispatch.
    sm, sr = c.load(smoke / 'manifest.json'), c.summarize(smoke)
    if sm['mode'] != 'smoke' or sm['sourceSha256'] != manifest['sourceSha256'] or sm['pricingSha256'] != manifest['pricingSha256'] or sm['upstreamUrl'] != manifest['upstreamUrl']:
        raise RuntimeError('Smoke does not match the formal source/pricing/route')
    if sr['criterion'] != 'pass' or len(sr['observations']) != 3 or any(r['status'] != 'completed' or r.get('cleanupError') for r in sr['observations']):
        raise RuntimeError('Smoke accounting, completion or cleanup failed')
    if c.load(root / 'smoke-verification.json')['reportSha256'] != sha((smoke / 'report.json').read_bytes()):
        raise RuntimeError('Smoke report changed since formal dispatch')
    head = c.clean_commit()
    remote = subprocess.check_output(['git', 'ls-remote', 'fork', 'refs/heads/feat/atlas-benchmark-cap256-20260911'], cwd=ROOT).decode().split()
    if not remote or remote[0] != head: raise RuntimeError('Source commit is not verified on the fork')
    if head != manifest['sourceCommit']: raise RuntimeError('Manifest must be prepared after the source commit')
    if c.load(ROOT / '.demo/runtime-source.json') != {'sourceSha256': manifest['sourceSha256'], 'warSha256': sha((ROOT / '.demo/artemis.war').read_bytes())}:
        raise RuntimeError('Runtime/source fingerprint mismatch; rebuild the copied server')


def resume(root, smoke, label, rerun=frozenset()):
    manifest = c.verify(root)
    preconditions(root, manifest, smoke)
    outcomes = c.load(root / 'outcomes.json')
    overrides = c.load(root / 'course-overrides.json') if (root / 'course-overrides.json').exists() else {}
    # A rerun replaces every observation of a course key on a freshly seeded course.
    for oid in rerun:
        key = next(x['courseKey'] for x in manifest['observations'] if x['observationId'] == oid)
        if key not in overrides: raise RuntimeError(f'{oid} needs a replacement course for {key}')
        chain = {x['observationId'] for x in manifest['observations'] if x['courseKey'] == key}
        if chain != rerun & chain: raise RuntimeError(f'Rerun must cover the whole {key} chain')
    for oid, v in outcomes.items():
        if oid in rerun: continue
        if v['status'] == 'dispatched':
            raise RuntimeError('An observation is still marked dispatched; inspect before resuming')
        if v['status'] in ('failed-or-partial',) or (v['status'] == 'invalid-or-unmeasured' and not untouched(root, oid, v)):
            raise RuntimeError(f'{oid} may have changed course state; refusing to resume')
    snapshot = root / f'outcomes-before-{label}.json'
    if snapshot.exists(): raise RuntimeError('Resume label already used')
    shutil.copyfile(root / 'outcomes.json', snapshot)
    prior = {oid: v for oid, v in outcomes.items() if v['status'] == 'invalid-or-unmeasured' or oid in rerun and v['status'] != 'skipped'}
    superseded = set()
    if rerun:
        events = [json.loads(line) for line in (root / 'provider-attempts.jsonl').read_text().splitlines()]
        superseded = {e['id'] for e in events if e['event'] == 'reserved' and e['observationId'] in rerun}
        path = root / 'superseded-attempts.json'
        record = c.load(path) if path.exists() else {}
        record[label] = {'observations': {oid: outcomes.get(oid, {}).get('status') for oid in sorted(rerun)},
                         'attemptIds': sorted(superseded), 'recordedAt': now(),
                         'reason': 'Interrupted by a Docker VM freeze; the course chain is rerun on a replacement course. '
                                   'These attempts count toward campaign spend but not toward per-observation results.'}
        c.write(path, record)
        for oid in rerun:
            outcomes[oid] = {'status': 'pending-rerun', 'rerunBy': label}
        c.write(root / 'outcomes.json', outcomes)

    pricing = c.load(root / 'pricing.json')
    meter = Meter(root, dict(manifest), pricing, manifest['upstreamUrl'])
    carry(meter, root, outcomes, label, superseded)
    if meter.pending or meter.blocked: raise RuntimeError('Ledger has unresolved reservations or a block')
    server = serve(meter, manifest['meterPort'])
    records = c.load(root / 'courses.json')['courses'] | {k: v for k, v in overrides.items()}
    stopped = None
    base, data = manifest['baseUrl'], seed.fixture()
    seed.JOURNAL = root / 'observation-writes.jsonl'
    try:
        for item in manifest['observations']:
            oid, record = item['observationId'], records[item['courseKey']]
            if outcomes.get(oid, {}).get('status') == 'completed':
                continue
            dependency = item.get('requiresObservationId')
            if stopped or dependency and outcomes.get(dependency, {}).get('status') != 'completed':
                outcomes[oid] = {'status': 'skipped', 'reason': stopped or 'Dependency did not complete'}
                c.write(root / 'outcomes.json', outcomes)
                continue
            # --- verbatim from campaign.execute(), plus priorAttempts/resumedBy bookkeeping ---
            c.verify(root)
            record['dailyCap'] = item['stage']
            observer = None
            outcomes[oid] = {'status': 'dispatched', 'startedAt': now(), 'evidenceComplete': False, 'resumedBy': label}
            if oid in prior: outcomes[oid]['priorAttempts'] = [prior[oid]]
            c.write(root / 'outcomes.json', outcomes)
            try:
                before = h.persisted_state(seed, data, base, record)
                if item['trigger'] == 'automatic': observer = Completion(base, seed.COOKIE_JAR, record['courseId'], ROOT / '.demo/server.log')
                meter.begin(oid)
                seed.LAST_RESULT = None
                started = h.trigger(seed, data, base, record, item, manifest['debounceSeconds'])
                result = observer.wait(1800) if observer else seed.LAST_RESULT
                if observer:
                    if result['exerciseCount'] != len(item['changedObjectKeys']): raise RuntimeError('Native batch size differs from schedule')
                    succeeded = result.get('status') == 'NO_OP' or result.get('failureCount') == 0
                else:
                    succeeded = result.get('status') in {'SUCCESS', 'NO_OP'}
                seed.set_automation(base, record, False, 3600)
                meter.end()
                outcomes[oid].update(status='completed' if succeeded else 'failed-or-partial', finishedAt=now(),
                                     durationSeconds=time.monotonic() - started, result=result, beforeState=before,
                                     afterState=h.persisted_state(seed, data, base, record), evidenceComplete=True)
                carry(meter, root, outcomes, label)
                if meter.pending or meter.blocked: stopped = 'Provider accounting unresolved or budget/configuration block'
            except Exception as error:
                outcomes[oid].update(status='invalid-or-unmeasured', error=type(error).__name__ + ': ' + str(error), finishedAt=now())
                stopped = 'Observation could have changed course state; stopped without replay'
            finally:
                if observer: observer.close()
                try:
                    seed.set_automation(base, record, False, 3600)
                except Exception as error:
                    outcomes[oid]['cleanupError'] = str(error)
                    outcomes[oid]['evidenceComplete'] = False
                    stopped = 'Course automation cleanup unresolved'
                c.write(root / 'outcomes.json', outcomes)
            print(oid, outcomes[oid]['status'], 'known EUR', meter.spent, flush=True)
    finally:
        server.shutdown()
        server.server_close()
        seed.JOURNAL = None
        c.summarize(root)


if __name__ == '__main__':
    campaign_root, smoke_root, resume_label = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
    rerun_ids = frozenset(sys.argv[4].split(',')) if len(sys.argv) > 4 else frozenset()
    if not campaign_root.is_relative_to(ROOT / '.demo'): raise SystemExit('Campaign must be under .demo')
    manifest = c.verify(campaign_root)
    seed.login(manifest['baseUrl'])
    seed.enable_features(manifest['baseUrl'])
    resume(campaign_root, smoke_root, resume_label, rerun_ids)
