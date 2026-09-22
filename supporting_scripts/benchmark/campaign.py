#!/usr/bin/env python3
"""Seed and measure the 90-observation Atlas campaign through ordinary interfaces."""
import argparse
import json
import os
import subprocess
import sys
import time
from collections import defaultdict
from decimal import Decimal as D
from pathlib import Path
from urllib.parse import urlsplit

import heterogeneous as h
import seed
from meter import Meter, append, cost, now, serve, sha
from observe import Completion

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CONDITIONS = ('maintained', 'assignment-only', 'mixed', 'bootstrap', 'single-exercise', 'lecture-maintenance')


def load(path):
    return json.loads(path.read_text())


def write(path, data):
    temp = path.with_suffix('.tmp')
    with open(temp, 'w') as out:
        json.dump(data, out, indent=2)
        out.write('\n')
        out.flush()
        os.fsync(out.fileno())
    temp.replace(path)


def fingerprint():
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    files = {p: sha((ROOT / p).read_bytes()) for p in sorted(set(paths))
             if p and not p.startswith('benchmark-results/') and (ROOT / p).is_file()}
    return seed.digest(files)


def clean_commit():
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip()
    if dirty: raise RuntimeError('Justin must commit the source before paid execution')
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()


def prepare(root, mode, base, upstream):
    root.mkdir(parents=True, exist_ok=False)
    rows = h.schedule(seed.fixture(), CONDITIONS, mode, 20260910, 10)
    manifest = dict(schemaVersion=1, campaignId=root.name, mode=mode, createdAt=now(),
                    baseUrl=base, upstreamUrl=upstream, meterPort=18091,
                    seed=20260910, debounceSeconds=10, criterionEur='15.00', budgetEur='1.00' if mode == 'smoke' else '20.00',
                    sourceSha256=fingerprint(), sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
                    pricingSha256=sha((HERE/'pricing.json').read_bytes()), fixtureSha256=seed.digest(seed.fixture()),
                    phaseConfiguration={k: {'model': 'gpt-5.6-luna', 'reasoningEffort': effort, 'apiRoute': '/v1/responses'}
                                        for k, effort in [('orchestration', 'xhigh'), ('worker', 'high'), ('flavor-strip', 'high')]},
                    observations=rows)
    write(root/'manifest.json', manifest)
    write(root/'pricing.json', load(HERE/'pricing.json'))
    print(f"Prepared {len(rows)} observations / {len({r['courseKey'] for r in rows})} courses: {root}")


def verify(root):
    m = load(root/'manifest.json')
    if m['sourceSha256'] != fingerprint() or m['fixtureSha256'] != seed.digest(seed.fixture()):
        raise RuntimeError('Source or fixture changed after manifest freeze')
    if m['pricingSha256'] != sha((root/'pricing.json').read_bytes()):
        raise RuntimeError('Pricing changed after manifest freeze')
    if urlsplit(m['baseUrl']).hostname not in {'localhost', '127.0.0.1'}:
        raise RuntimeError('Isolated local Artemis instance required')
    return m


def seed_courses(root, manifest):
    if (root/'courses.json').exists(): raise RuntimeError('Courses already seeded; refusing overwrite')
    if (root/'seed-started.json').exists(): raise RuntimeError('Earlier seeding may have written data; inspect its journal, do not replay')
    write(root/'seed-started.json', {'at': now()})
    seed.JOURNAL = root/'seed-writes.jsonl'
    records = {}
    try:
        for key in dict.fromkeys(x['courseKey'] for x in manifest['observations']):
            items = [x for x in manifest['observations'] if x['courseKey'] == key]
            records.update(h.create_fixtures(seed, seed.fixture(), manifest['baseUrl'], items, 3600, manifest['campaignId'])['courses'])
            write(root/'seed-progress.json', {'courses': records})
            print(f"Seeded {key} ({len(records)} courses)", flush=True)
        write(root/'courses.json', {'courses': records})
    finally:
        seed.JOURNAL = None


def summarize(root):
    m, pricing = load(root/'manifest.json'), load(root/'pricing.json')
    events = [json.loads(x) for x in (root/'provider-attempts.jsonl').read_text().splitlines()] if (root/'provider-attempts.jsonl').exists() else []
    outcomes = load(root/'outcomes.json') if (root/'outcomes.json').exists() else {}
    attempts, settlements, blocked = {}, {}, set()
    for e in events:
        if e['event'] == 'reserved':
            if e['id'] in attempts: raise ValueError('Duplicate attempt ID')
            attempts[e['id']] = e
        if e['event'] == 'settled':
            if e['id'] in settlements: raise ValueError('Duplicate settlement')
            reproduced = cost(e, pricing)
            if reproduced != D(e['costEur']): raise ValueError('Cost reproduction failed')
            settlements[e['id']] = reproduced
        if e['event'] == 'blocked': blocked.add(e.get('observationId'))
    if not settlements.keys() <= attempts.keys(): raise ValueError('Settlement without reservation')
    grouped = defaultdict(list)
    for ident, a in attempts.items(): grouped[a['observationId']].append((a, settlements.get(ident)))
    rows = []
    source_ok = m['sourceSha256'] == fingerprint() and m['pricingSha256'] == sha((root/'pricing.json').read_bytes())
    for item in m['observations']:
        oid = item['observationId']
        outcome = outcomes.get(oid, {'status': 'not-dispatched'})
        values = grouped[oid]
        complete = source_ok and oid not in blocked and outcome.get('evidenceComplete', False) and all(v is not None for _, v in values)
        known = sum((v for _, v in values if v is not None), D(0))
        for a, _ in values:
            expected = m['phaseConfiguration'].get(a['phase'])
            if not expected or any(a.get(actual) != expected[required] for actual, required in [('requestedModel', 'model'), ('reasoningEffort', 'reasoningEffort'), ('apiRoute', 'apiRoute')]):
                complete = False
        rows.append(item | outcome | {'knownCostEur': str(known), 'costEur': str(known) if complete else None,
                                      'accountingComplete': complete, 'providerAttempts': len(values),
                                      'reservedEur': str(sum((D(a['reservationEur']) for a, v in values if v is None), D(0)))})
    criterion = 'fail' if any(D(r['knownCostEur']) > D(m['criterionEur']) for r in rows) else 'pass' if rows and all(r['accountingComplete'] for r in rows) else 'incomplete'
    result = {'campaignId': m['campaignId'], 'criterionEur': m['criterionEur'], 'criterion': criterion,
              'knownCostEur': str(sum(settlements.values(), D(0))), 'sourceVerified': source_ok,
              'reservedEur': str(sum((D(a['reservationEur']) for i, a in attempts.items() if i not in settlements), D(0))),
              'observations': rows}
    write(root/'report.json', result)
    return result


def execute(root, manifest, smoke=None):
    if manifest['mode'] == 'formal':
        if smoke is None: raise RuntimeError('Formal dispatch requires the verified smoke campaign')
        sm = load(smoke/'manifest.json')
        sr = summarize(smoke)
        if sm['mode'] != 'smoke' or sm['sourceSha256'] != manifest['sourceSha256'] or sm['pricingSha256'] != manifest['pricingSha256'] or sm['upstreamUrl'] != manifest['upstreamUrl']:
            raise RuntimeError('Smoke does not match the formal source/pricing/route')
        if sr['criterion'] != 'pass' or len(sr['observations']) != 3 or any(r['status'] != 'completed' or r.get('cleanupError') for r in sr['observations']):
            raise RuntimeError('Smoke accounting, completion or cleanup failed')
        phases = {e['phase'] for e in map(json.loads, (smoke/'provider-attempts.jsonl').read_text().splitlines()) if e['event'] == 'reserved'}
        if phases != set(manifest['phaseConfiguration']):
            raise RuntimeError('Smoke did not exercise all three provider phases')
        if D(sr['knownCostEur']) + D(sr['reservedEur']) > D('1'):
            raise RuntimeError('Smoke ceiling exceeded')
        write(root/'smoke-verification.json', {'campaignId': sm['campaignId'], 'reportSha256': sha((smoke/'report.json').read_bytes())})
    head = clean_commit()
    remote = subprocess.check_output(['git', 'ls-remote', 'fork', 'refs/heads/feat/atlas-benchmark-cap256-20260911'], cwd=ROOT).decode().split()
    if not remote or remote[0] != head: raise RuntimeError('Source commit is not verified on the fork')
    if head != manifest['sourceCommit']: raise RuntimeError('Manifest must be prepared after the source commit')
    runtime = load(ROOT/'.demo/runtime-source.json')
    if runtime != {'sourceSha256': manifest['sourceSha256'], 'warSha256': sha((ROOT/'.demo/artemis.war').read_bytes())}:
        raise RuntimeError('Runtime/source fingerprint mismatch; rebuild the copied server')
    if (root/'outcomes.json').exists(): raise RuntimeError('Campaign has dispatched observations; never replay it')
    pricing = load(root/'pricing.json')
    meter = Meter(root, manifest, pricing, manifest['upstreamUrl'])
    server = serve(meter, manifest['meterPort'])
    records = load(root/'courses.json')['courses']
    outcomes, stopped = {}, None
    base, data = manifest['baseUrl'], seed.fixture()
    seed.JOURNAL = root/'observation-writes.jsonl'
    try:
        for item in manifest['observations']:
            oid, record = item['observationId'], records[item['courseKey']]
            dependency = item.get('requiresObservationId')
            if stopped or dependency and outcomes.get(dependency, {}).get('status') != 'completed':
                outcomes[oid] = {'status': 'skipped', 'reason': stopped or 'Dependency did not complete'}
                write(root/'outcomes.json', outcomes)
                continue
            verify(root)
            record['dailyCap'] = item['stage']  # Native course setting prevents automatic replay after failure.
            observer = None
            outcomes[oid] = {'status': 'dispatched', 'startedAt': now(), 'evidenceComplete': False}
            write(root/'outcomes.json', outcomes)  # Durable dispatch marker BEFORE the first course mutation.
            try:
                before = h.persisted_state(seed, data, base, record)
                if item['trigger'] == 'automatic': observer = Completion(base, seed.COOKIE_JAR, record['courseId'], ROOT/'.demo/server.log')
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
                                     durationSeconds=time.monotonic()-started, result=result, beforeState=before,
                                     afterState=h.persisted_state(seed, data, base, record), evidenceComplete=True)
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
                write(root/'outcomes.json', outcomes)
            print(oid, outcomes[oid]['status'], 'known EUR', meter.spent, flush=True)
    finally:
        # Closing the meter rejects any later automatic retry rather than spending invisibly.
        server.shutdown()
        server.server_close()
        seed.JOURNAL = None
        summarize(root)


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['prepare', 'seed', 'run', 'report', 'stamp-build'])
    p.add_argument('--campaign', type=Path)
    p.add_argument('--mode', choices=['smoke', 'formal'], default='smoke')
    p.add_argument('--base-url', default='http://127.0.0.1:8083')
    p.add_argument('--upstream', default='https://api.openai.com/v1')
    p.add_argument('--paid', action='store_true')
    p.add_argument('--smoke', type=Path, help='Completed matching smoke campaign, required for formal dispatch')
    args = p.parse_args()
    if args.command == 'stamp-build':
        write(ROOT/'.demo/runtime-source.json', {'sourceSha256': fingerprint(), 'warSha256': sha((ROOT/'.demo/artemis.war').read_bytes())})
        return
    if args.campaign is None: p.error('--campaign is required')
    root = args.campaign.resolve()
    if not root.is_relative_to(ROOT/'.demo'):
        p.error('Operational evidence must stay in the copied checkout’s ignored .demo directory')
    if args.command == 'prepare':
        prepare(root, args.mode, args.base_url, args.upstream)
        return
    if args.command == 'report':
        print(json.dumps(summarize(root), indent=2))
        return
    manifest = verify(root)
    if args.command == 'run' and not args.paid: p.error('Paid dispatch requires --paid')
    seed.login(manifest['baseUrl'])
    seed.enable_features(manifest['baseUrl'])
    if args.command == 'seed': seed_courses(root, manifest)
    else: execute(root, manifest, args.smoke.resolve() if args.smoke else None)


if __name__ == '__main__':
    main()
