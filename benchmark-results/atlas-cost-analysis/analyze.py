#!/usr/bin/env python3
"""Recompute an archived Atlas cost campaign offline from its meter ledger.

Reads the evidence package written by supporting_scripts/benchmark (manifest, pricing,
outcomes, provider-attempts ledger) and never imports the live runner or meter. Every
settled attempt's cost is recomputed from its recorded usage and the frozen pricing and
must equal the ledger value. An attempt without settled usage is never estimated: it is
carried at its recorded worst-case reservation as an explicit upper bound.
"""
import argparse
import csv
import hashlib
import json
import random
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal as D
from pathlib import Path
from statistics import median

CONDITIONS = ['maintained', 'assignment-only', 'mixed', 'bootstrap', 'single-exercise', 'lecture-maintenance']
NAMES = dict(zip(CONDITIONS, ['Maintained', 'Assignment', 'Mixed', 'Bootstrap', 'Single exercise', 'Lecture unit']))
# Ledger phase names mapped to the keys of the earlier summary format.
PHASES = {'flavor-strip': 'flavor_strip', 'orchestration': 'orchestration', 'worker': 'worker'}
PHASE_LABELS = {'flavor_strip': 'Flavor stripping', 'orchestration': 'Orchestration', 'worker': 'Workers'}
EFFORTS = {'flavor-strip': 'high', 'orchestration': 'xhigh', 'worker': 'high'}
TOKENS = ['inputTokens', 'cachedInputTokens', 'cacheWriteInputTokens', 'uncachedInputTokens', 'outputTokens', 'reasoningTokens']
READS = {'getExerciseContent', 'getLectureUnitContent', 'getCompetencyDetails', 'listCompetencyIndex', 'searchLectureContent'}
WRITES = {'assignExerciseToCompetency', 'assignLectureUnitToCompetency', 'unassignExerciseFromCompetency',
          'unassignLectureUnitFromCompetency', 'createCompetency', 'editCompetency'}
DELEGATIONS = {'delegateToAssigner', 'delegateToCreator', 'delegateToEditor'}
TERMINALS = {'completeOrchestration', 'completeWorkerTask'}
CRITERION = D('15.00')
CHECKSUMS = 'checksums.json'


class AuditError(ValueError):
    """Evidence cannot support the requested published calculation."""


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, default=str) + '\n')


def statistics(values):
    return dict(minimum=min(values), median=median(values), maximum=max(values), total=sum(values))


def require(ok, message):
    if not ok:
        raise AuditError(message)


def when(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def package_hashes(evidence):
    return {str(p.relative_to(evidence)): sha(p) for p in sorted(evidence.rglob('*')) if p.is_file() and p.name != CHECKSUMS}


def verify_package(evidence):
    expected = json.loads((evidence / CHECKSUMS).read_text())
    actual = package_hashes(evidence)
    require(actual == expected, 'Package checksum mismatch: ' + ', '.join(sorted(k for k in actual.keys() | expected.keys() if actual.get(k) != expected.get(k))))
    return expected


def usage_counts(settled):
    """Validate one settled Responses usage record and return its token categories."""
    usage = settled.get('usage')
    require(isinstance(usage, dict), 'Missing usage: ' + settled['id'])
    details, output = usage.get('input_tokens_details') or {}, usage.get('output_tokens_details') or {}
    values = [usage.get('input_tokens'), details.get('cached_tokens'), details.get('cache_write_tokens', 0), usage.get('output_tokens'), output.get('reasoning_tokens', 0)]
    require(all(type(v) is int and v >= 0 for v in values), 'Invalid usage: ' + settled['id'])
    i, c, w, o, r = values
    require(c + w <= i and r <= o and usage.get('total_tokens') == i + o, 'Inconsistent usage: ' + settled['id'])
    require(set(details) <= {'cached_tokens', 'cache_write_tokens'} and set(output) <= {'reasoning_tokens'}, 'Unsupported usage category: ' + settled['id'])
    return dict(inputTokens=i, cachedInputTokens=c, cacheWriteInputTokens=w, uncachedInputTokens=i - c - w, outputTokens=o, reasoningTokens=r)


def attempt_cost(tokens, model, pricing):
    """Frozen USD rates divided by the frozen USD-per-EUR rate, in Decimal precision 28."""
    rates = pricing['models'][model]
    usd = [D(rates[k]) for k in ['inputUsdPerMillion', 'cachedInputUsdPerMillion', 'cacheWriteInputUsdPerMillion', 'outputUsdPerMillion']]
    fx = D(pricing['usdPerEur'])
    require(fx.is_finite() and fx > 0 and all(v.is_finite() and v >= 0 for v in usd), 'Invalid frozen price or exchange rate')
    i = tokens['inputTokens']
    require(i <= rates['maxSupportedInputTokens'] and tokens['outputTokens'] <= rates['maxOutputTokens'], 'Usage outside supported pricing')
    if i > rates['longContext']['thresholdTokens']:
        tier = rates['longContext']
        usd = [v * D(str(tier['inputMultiplier'])) for v in usd[:3]] + [usd[3] * D(str(tier['outputMultiplier']))]
    counts = [tokens['uncachedInputTokens'], tokens['cachedInputTokens'], tokens['cacheWriteInputTokens'], tokens['outputTokens']]
    return sum(D(v) * rate for v, rate in zip(counts, usd)) / D(1_000_000) / fx


def analyze(evidence):
    hashes = verify_package(evidence)
    j = lambda n: json.loads((evidence / n).read_text())
    jl = lambda n: [json.loads(line) for line in (evidence / n).read_text().splitlines() if line.strip()]
    manifest, pricing, outcomes, report = j('manifest.json'), j('pricing.json'), j('outcomes.json'), j('report.json')
    courses, ledger = j('courses.json')['courses'], jl('provider-attempts.jsonl')
    carried = j('resume-carried-reservations.json') if (evidence / 'resume-carried-reservations.json').exists() else {}
    schedule, campaign = manifest['observations'], manifest['campaignId']
    stages = max(x['stage'] for x in schedule)
    checks = []

    def check(name, ok):
        checks.append({'check': name, 'passed': bool(ok)})
        require(ok, name)

    ids = [x['observationId'] for x in schedule]
    check('unique formal schedule of 90 observations', manifest['mode'] == 'formal' and len(ids) == len(set(ids)) == 90)
    check('six primary conditions repeated ten times', Counter(x['condition'] for x in schedule if x['stage'] == 1) == Counter({c: 10 for c in CONDITIONS}))
    for rep in range(1, 11):
        order = list(CONDITIONS)
        random.Random(manifest['seed'] + rep * 1_000_003).shuffle(order)
        check(f'repetition {rep}: seeded order and complete bootstrap chain',
              order == [x['condition'] for x in schedule if x['stage'] == 1 and x['repetition'] == rep]
              and sorted(x['stage'] for x in schedule if x['courseKey'] == f'r{rep:02}-bootstrap') == list(range(1, stages + 1)))
    check('60 isolated fixture courses', len(courses) == len({x['courseId'] for x in courses.values()}) == 60 and set(courses) == {x['courseKey'] for x in schedule})
    check('disjoint exercise identities', len({i for x in courses.values() for i in x['exerciseIds']}) == 3600)
    check('frozen pricing', sha(evidence / 'pricing.json') == manifest['pricingSha256'] and set(pricing['models']) == {'gpt-5.6-luna'})
    check('frozen phase configuration', manifest['phaseConfiguration'] == {p: {'model': 'gpt-5.6-luna', 'reasoningEffort': EFFORTS[p], 'apiRoute': '/v1/responses'} for p in PHASES})
    smoke = evidence / 'smoke'
    if smoke.exists():
        sm, verification = json.loads((smoke / 'manifest.json').read_text()), j('smoke-verification.json')
        check('matching smoke campaign', sm['mode'] == 'smoke' and sm['campaignId'] == verification['campaignId'] and sha(smoke / 'report.json') == verification['reportSha256']
              and all(sm[k] == manifest[k] for k in ['sourceSha256', 'pricingSha256', 'upstreamUrl']))

    # Ledger: one reservation per attempt, then at most one terminal event.
    reserved, terminal = {}, {}
    for e in ledger:
        if e['event'] == 'reserved':
            require(e['id'] not in reserved, 'Duplicate reservation: ' + e['id'])
            reserved[e['id']] = e
        elif e['event'] in {'settled', 'unknown'}:
            require(e['id'] in reserved and e['id'] not in terminal, 'Terminal event without single reservation: ' + e['id'])
            terminal[e['id']] = e
        else:
            raise AuditError('Unexpected ledger event: ' + e['event'])
    check('every attempt terminated', set(reserved) == set(terminal))
    check('attempts belong to scheduled observations', {a['observationId'] for a in reserved.values()} <= set(ids))
    check('frozen route, model and effort for every attempt',
          all(a['apiRoute'] == '/v1/responses' and a['requestedModel'] == 'gpt-5.6-luna' and a['phase'] in PHASES and a['reasoningEffort'] == EFFORTS[a['phase']] for a in reserved.values()))
    settled = {k: v for k, v in terminal.items() if v['event'] == 'settled'}
    unsettled = {k: v for k, v in terminal.items() if v['event'] == 'unknown'}
    check('settled responses completed with the frozen model', all(v['httpStatus'] == 200 and v['responseStatus'] == 'completed' and v['model'] == 'gpt-5.6-luna' for v in settled.values()))
    tokens, costs, differences = {}, {}, []
    for k, v in settled.items():
        tokens[k] = usage_counts(v)
        costs[k] = attempt_cost(tokens[k], v['model'], pricing)
        differences.append(abs(costs[k] - D(v['costEur'])))
    check('every settled cost reproduced from usage and frozen pricing', max(differences) < D('1e-24'))
    # Unsettled attempts: a definitive provider error, carried by the operator resume as an upper bound.
    check('unsettled attempts are definitive provider errors carried by the resume',
          all(v['httpStatus'] >= 400 and v['providerRequestId'] and k in carried and not carried[k]['superseded']
              and D(carried[k]['reservationEur']) == D(reserved[k]['reservationEur']) for k, v in unsettled.items()))
    upper = {k: D(reserved[k]['reservationEur']) for k in unsettled}

    report_rows = {x['observationId']: x for x in report['observations']}
    check('complete report identities', set(report_rows) == set(ids) and report['sourceVerified'] is True)
    by_observation = defaultdict(list)
    for k, a in reserved.items():
        by_observation[a['observationId']].append(k)
    rows, tool_totals = [], Counter()
    for plan in schedule:
        oid, outcome, record = plan['observationId'], outcomes[plan['observationId']], courses[plan['courseKey']]
        attempts = sorted(by_observation[oid], key=lambda k: reserved[k]['at'])
        result = outcome.get('result') or {}
        check(f'{oid}: completed with complete evidence and cleanup', outcome['status'] == 'completed' and outcome['evidenceComplete'] is True and not outcome.get('cleanupError') and attempts)
        if plan['trigger'] == 'automatic':
            check(f'{oid}: native batch outcome', result.get('outcome') in {'SUCCESS'} and result.get('failureCount') == 0 and result.get('exerciseCount') == len(plan['changedObjectKeys'])
                  or result.get('status') == 'NO_OP')
        else:
            check(f'{oid}: manual outcome', result.get('status') in {'SUCCESS', 'NO_OP'})
        before, after = outcome['beforeState'], outcome['afterState']
        previous = outcomes[plan['requiresObservationId']]['afterState'] if plan.get('requiresObservationId') else record['initialState']
        check(f'{oid}: starts from the recorded preceding state', previous == before and before['courseId'] == after['courseId'] == record['courseId'] and before['exerciseCount'] == after['exerciseCount'] == 60)
        counts = Counter(t['name'] for k in attempts if k in settled for t in settled[k].get('tools') or [])
        check(f'{oid}: recognized tools', set(counts) <= READS | WRITES | DELEGATIONS | TERMINALS)
        tool_totals.update(counts)
        events = [when(reserved[k]['at']) for k in attempts] + [when(terminal[k]['at']) for k in attempts]
        known = sum((costs[k] for k in attempts if k in settled), D(0))
        bound = known + sum((upper[k] for k in attempts if k in upper), D(0))
        check(f'{oid}: known cost matches the harness report', abs(known - D(report_rows[oid]['knownCostEur'])) < D('1e-24'))
        row = {k: plan[k] for k in ['observationId', 'invocationId', 'courseKey', 'condition', 'repetition', 'stage', 'trigger', 'scheduleIndex']}
        row.update(status='completed', outcome=result.get('outcome') or result.get('status'),
                   costEur=known, upperBoundCostEur=bound, accountingComplete=bound == known,
                   providerRequests=len(attempts), unsettledRequests=sum(k in upper for k in attempts),
                   callbacks=sum(counts.values()), reads=sum(counts[k] for k in READS), writes=sum(counts[k] for k in WRITES),
                   delegations=sum(counts[k] for k in DELEGATIONS), completionCallbacks=sum(counts[k] for k in TERMINALS),
                   executionSeconds=D(str((max(events) - min(events)).total_seconds())),
                   eventToCompletionSeconds=D(str(outcome['durationSeconds'])),
                   beforeState=before, afterState=after, tools=dict(counts),
                   changedObjectTypes=plan.get('changedObjectTypes', []), changedObjectKeys=plan.get('changedObjectKeys', []),
                   resumedBy=outcome.get('resumedBy'))
        row['overheadSeconds'] = row['eventToCompletionSeconds'] - row['executionSeconds']
        for key in TOKENS:
            row[key] = sum(tokens[k][key] for k in attempts if k in settled)
        row['phaseCostsEur'] = {PHASES[p]: sum((costs[k] for k in attempts if k in settled and reserved[k]['phase'] == p), D(0)) for p in PHASES}
        rows.append(row)
    check('sequential observations', all(when(outcomes[a]['finishedAt']) <= when(outcomes[b]['startedAt'])
                                         for a, b in zip(*[sorted(ids, key=lambda i: outcomes[i]['startedAt'])[x:] for x in (0, 1)])))

    metrics = ['costEur', 'upperBoundCostEur', *TOKENS, 'providerRequests', 'unsettledRequests', 'callbacks', 'reads', 'writes', 'delegations',
               'completionCallbacks', 'executionSeconds', 'eventToCompletionSeconds', 'overheadSeconds']
    aggregate = lambda data: {'n': len(data), **{k: statistics([x[k] for x in data]) for k in metrics}}
    primary = {c: aggregate([r for r in rows if r['condition'] == c and r['stage'] == 1]) for c in CONDITIONS}
    continuations = {str(s): aggregate([r for r in rows if r['stage'] == s]) for s in range(2, stages + 1)}
    workflows = [{'courseKey': f'r{rep:02}-bootstrap', 'repetition': rep,
                  'costEur': sum(r['costEur'] for r in rows if r['courseKey'] == f'r{rep:02}-bootstrap'),
                  'upperBoundCostEur': sum(r['upperBoundCostEur'] for r in rows if r['courseKey'] == f'r{rep:02}-bootstrap'),
                  'stages': [r['observationId'] for r in rows if r['courseKey'] == f'r{rep:02}-bootstrap']} for rep in range(1, 11)]
    total = sum(r['costEur'] for r in rows)
    phases = {}
    for phase, key in PHASES.items():
        attempts = [k for k, a in reserved.items() if a['phase'] == phase]
        cost = sum((costs[k] for k in attempts if k in settled), D(0))
        phases[key] = {'requests': len(attempts), 'unsettledRequests': sum(k in upper for k in attempts),
                       'requestSharePercent': D(len(attempts)) / len(reserved) * 100, 'costEur': cost, 'costSharePercent': cost / total * 100,
                       **{t: sum(tokens[k][t] for k in attempts if k in settled) for t in TOKENS if t != 'uncachedInputTokens'}}
    maximum = max(rows, key=lambda r: r['costEur'])
    maximum_bound = max(rows, key=lambda r: r['upperBoundCostEur'])
    settled_inputs = [t['inputTokens'] for t in tokens.values()]
    summary = {
        'campaignId': campaign, 'observations': len(rows), 'primaryCount': 60, 'continuationCount': len(rows) - 60, 'bootstrapStages': stages,
        'providerAttempts': len(reserved), 'settledAttempts': len(settled), 'unsettledAttempts': len(unsettled),
        'totalCostEur': total, 'unsettledUpperBoundEur': sum(upper.values(), D(0)), 'totalUpperBoundEur': total + sum(upper.values(), D(0)),
        'primaryCostEur': sum(r['costEur'] for r in rows if r['stage'] == 1),
        'continuationCostEur': sum(r['costEur'] for r in rows if r['stage'] > 1),
        'maximumCostEur': maximum['costEur'], 'maximumObservationId': maximum['observationId'],
        'maximumUpperBoundEur': maximum_bound['upperBoundCostEur'], 'maximumUpperBoundObservationId': maximum_bound['observationId'],
        'criterionEur': CRITERION, 'marginEur': CRITERION - maximum_bound['upperBoundCostEur'],
        'criterion': 'pass' if maximum_bound['upperBoundCostEur'] <= CRITERION else 'fail',
        'criterionBasis': 'Maximum per-invocation upper bound: settled costs plus the worst-case reservation of any unsettled attempt.',
        'accountingComplete': not unsettled,
        'unsettled': [{'attemptId': k, 'observationId': reserved[k]['observationId'], 'phase': reserved[k]['phase'], 'httpStatus': v['httpStatus'],
                       'providerRequestId': v['providerRequestId'], 'reservationEur': upper[k]} for k, v in unsettled.items()],
        'primary': primary, 'continuations': continuations, 'bootstrapWorkflows': workflows,
        'bootstrapWorkflowCosts': statistics([x['costEur'] for x in workflows]), 'phases': phases, 'allObservations': aggregate(rows),
        'toolTotals': dict(tool_totals), 'outcomes': dict(Counter(r['outcome'] for r in rows)),
        'maximumAttemptInputTokens': max(settled_inputs), 'longContextAttempts': sum(i > 272000 for i in settled_inputs),
        'overheadByTrigger': {t: statistics([r['overheadSeconds'] for r in rows if r['trigger'] == t]) for t in ['automatic', 'manual']},
        'resumes': sorted({r['resumedBy'] for r in rows if r['resumedBy']}),
        'resumedObservations': sum(bool(r['resumedBy']) for r in rows),
        'typeCoverage': {t: {'targetedObjectOccurrences': sum(r['changedObjectTypes'].count(t) for r in rows), 'observations': sum(t in r['changedObjectTypes'] for r in rows)}
                         for t in sorted({t for r in rows for t in r['changedObjectTypes']})},
        'finalCompetencyCounts': {r['courseKey']: r['afterState']['competencyCount'] for r in rows},
        'definitions': {
            'costEur': 'Sum of settled attempt costs recomputed from recorded usage and frozen pricing.',
            'upperBoundCostEur': 'costEur plus the worst-case reservation of each unsettled attempt.',
            'callbacks': 'Function calls issued by the model in settled responses, as recorded by the request meter.',
            'executionSeconds': 'Model-active span: first provider request to last provider response of the observation.',
            'eventToCompletionSeconds': 'Harness trigger to observed completion; includes the inactivity window for automatic runs.',
        },
    }
    verify_package(evidence)
    audit = {'campaignId': campaign, 'checks': checks, 'failedChecks': [], 'packageFilesVerified': len(hashes),
             'maximumLedgerCostDifferenceEur': max(differences),
             'accounting': 'Decimal precision 28. Apply frozen USD rates then divide by frozen USD per EUR; round only for display.'}
    return rows, summary, audit


def figures(rows, summary, out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FormatStrFormatter, PercentFormatter
    matplotlib.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 9, 'svg.fonttype': 'none', 'svg.hashsalt': summary['campaignId'],
                                'axes.spines.top': False, 'axes.spines.right': False})
    out.mkdir(parents=True, exist_ok=True)
    primary = [r for r in rows if r['stage'] == 1]
    limit = float(max(r['costEur'] for r in primary)) * 1.08
    fig, ax = plt.subplots(figsize=(5.85, 3.25), layout='constrained')
    for i, c in enumerate(CONDITIONS):
        values = [r for r in primary if r['condition'] == c]
        xs = [float(r['costEur']) for r in values]
        ax.hlines(i, min(xs), max(xs), color='#777777', linewidth=1)
        ax.scatter(xs, [i + (r['repetition'] - 5.5) * .026 for r in values], s=22, color='#256080', alpha=.85, zorder=3)
        ax.plot([float(median(xs))], [i], 'D', markersize=5, color='#111111', zorder=4)
        ax.text(1.03, i, f"{D(summary['primary'][c]['costEur']['median']):.4f}", transform=ax.get_yaxis_transform(), ha='left', va='center')
    ax.text(1.03, -.75, 'Median', transform=ax.get_yaxis_transform(), ha='left', va='center', fontweight='bold')
    ax.set_yticks(range(6), [NAMES[c] for c in CONDITIONS])
    ax.invert_yaxis()
    ax.set_xlabel('Provider cost per invocation (EUR)')
    ax.set_xlim(0, limit)
    ax.xaxis.set_major_formatter(FormatStrFormatter('%.3f'))
    ax.grid(axis='x', alpha=.2)
    fig.savefig(out / 'primary-costs.svg', metadata={'Date': None})
    plt.close(fig)

    stages = list(range(1, summary['bootstrapStages'] + 1))
    chain = [r for r in rows if r['condition'] == 'bootstrap']
    fig, ax = plt.subplots(figsize=(5.85, 3.0), layout='constrained')
    for rep in range(1, 11):
        values = sorted([r for r in chain if r['repetition'] == rep], key=lambda r: r['stage'])
        ax.plot(stages, [float(r['costEur']) for r in values], color='#b5c5ce', lw=.85, marker='o', markersize=3)
    ax.plot(stages, [float(median(r['costEur'] for r in chain if r['stage'] == s)) for s in stages], color='#1e5674', lw=2, marker='D', markersize=5, label='Stage median')
    ax.set_xticks(stages, ['Primary\n12 objects', 'Continuation 1\n24 objects', 'Continuation 2\n24 objects', 'Continuation 3\n12 objects'][:len(stages)])
    ax.set_ylabel('Provider cost per invocation (EUR)')
    ax.set_ylim(0, float(max(r['costEur'] for r in chain)) * 1.1)
    ax.yaxis.set_major_formatter(FormatStrFormatter('%.3f'))
    ax.grid(axis='y', alpha=.2)
    ax.legend(frameon=False, loc='upper left')
    fig.savefig(out / 'bootstrap-stages.svg', metadata={'Date': None})
    plt.close(fig)

    order = list(PHASE_LABELS)
    request_shares = [summary['phases'][p]['requestSharePercent'] for p in order]
    cost_shares = [summary['phases'][p]['costSharePercent'] for p in order]
    matplotlib.rcParams['svg.hashsalt'] = summary['campaignId'] + '-phase-shares'
    fig, ax = plt.subplots(figsize=(5.85, 2.8), layout='constrained')
    ax.set_axisbelow(True)
    ax.grid(axis='x', color='#d8d8d8', linewidth=0.6)
    for values, offset, label, color, edge, hatch in [(request_shares, -0.18, 'Requests', '#256080', '#256080', None),
                                                      (cost_shares, 0.18, 'Cost', '#b5c5ce', '#6a8798', '///')]:
        bars = ax.barh([i + offset for i in range(3)], [float(v) for v in values], height=0.30, label=label, color=color, edgecolor=edge, linewidth=0.55, hatch=hatch)
        ax.bar_label(bars, labels=[f'{v:.1f}%' for v in values], padding=4, fontsize=9)
    ax.set_yticks(range(3), [PHASE_LABELS[p] for p in order])
    ax.invert_yaxis()
    ax.tick_params(axis='y', length=0, pad=7)
    ax.spines['left'].set_visible(False)
    top = int(max(float(v) for v in request_shares + cost_shares) // 10 + 2) * 10
    ax.set_xlim(0, top)
    ax.set_xticks(range(0, top + 1, 10))
    ax.xaxis.set_major_formatter(PercentFormatter(xmax=100, decimals=0))
    ax.set_xlabel('Share of campaign total')
    ax.legend(frameon=False, ncols=2, loc='lower left', bbox_to_anchor=(0, 1.01), borderaxespad=0)
    fig.savefig(out / 'phase-shares.svg', metadata={'Date': None})
    plt.close(fig)


def typst_tables(rows, summary, directory):
    def cell(x):
        return '[' + str(x) + ']'

    def table(headers, values, widths=None, size='8.5pt'):
        cols = widths or ', '.join(['1fr'] * len(headers))
        return ('#text(size: ' + size + ')[#set par(leading: 0.55em, first-line-indent: 0pt)\n#table(columns: (' + cols + '), inset: 3pt, stroke: 0.4pt, align: left, table.header('
                + ', '.join(cell('*' + h + '*') for h in headers) + '),\n' + '\n'.join(', '.join(cell(x) for x in row) + ',' for row in values) + '\n)]')
    euro = lambda v: f'{D(v):.6f}'
    number = lambda v: f'{v:,}'
    tokenfmt = lambda v: f'{int(v):,}' if v == int(v) else f'{v:,.1f}'
    rangefmt = lambda s: f"{tokenfmt(s['median'])} ({tokenfmt(s['minimum'])} to {tokenfmt(s['maximum'])})"
    seconds = lambda s: f"{s['median']:.2f} ({s['minimum']:.2f} to {s['maximum']:.2f})"
    marked = lambda r: euro(r['costEur']) + ('#super[a]' if not r['accountingComplete'] else '')
    stages = range(2, summary['bootstrapStages'] + 1)
    entries = {
        'primary-cost': table(['Condition', 'n', 'Median EUR', 'Min. EUR', 'Max. EUR'], [[NAMES[c], 10, *[euro(summary['primary'][c]['costEur'][k]) for k in ['median', 'minimum', 'maximum']]] for c in CONDITIONS], '1.5fr, 0.3fr, 1fr, 1fr, 1fr'),
        'primary-operations': table(['Condition', 'Requests', 'Tool calls', 'Execution (s)'], [[NAMES[c], rangefmt(summary['primary'][c]['providerRequests']), rangefmt(summary['primary'][c]['callbacks']), seconds(summary['primary'][c]['executionSeconds'])] for c in CONDITIONS], '1.1fr, 1fr, 1fr, 1.6fr'),
        'primary-tokens': table(['Condition', 'Input tokens', 'Output tokens'], [[NAMES[c], rangefmt(summary['primary'][c]['inputTokens']), rangefmt(summary['primary'][c]['outputTokens'])] for c in CONDITIONS], '1fr, 1.8fr, 1.65fr'),
        'phases': table(['Phase', 'Requests', 'Cost EUR', 'Cost share'], [[PHASE_LABELS[p], number(s['requests']), euro(s['costEur']), f"{s['costSharePercent']:.1f}%"] for p, s in summary['phases'].items()], '1.5fr, 0.8fr, 1.1fr, 1fr'),
        'continuations': table(['Stage', 'n', 'Median EUR', 'Min. EUR', 'Max. EUR'], [[f'Continuation {s - 1}', 10, *[euro(summary['continuations'][str(s)]['costEur'][k]) for k in ['median', 'minimum', 'maximum']]] for s in stages], '1.5fr, 0.3fr, 1fr, 1fr, 1fr'),
        'continuation-operations': table(['Stage', 'Requests', 'Tool calls', 'Execution (s)'], [[f'Continuation {s - 1}', rangefmt(summary['continuations'][str(s)]['providerRequests']), rangefmt(summary['continuations'][str(s)]['callbacks']), seconds(summary['continuations'][str(s)]['executionSeconds'])] for s in stages], '1.1fr, 1fr, 1fr, 1.6fr'),
        'type-coverage': table(['Target object type', 'Observations', 'Target occurrences'], [[k.replace(':', ' / '), v['observations'], v['targetedObjectOccurrences']] for k, v in summary['typeCoverage'].items()], '2fr, 1fr, 1fr'),
        'workflows': table(['Repetition', 'Workflow EUR', 'Repetition', 'Workflow EUR'], [[i + 1, euro(summary['bootstrapWorkflows'][i]['costEur']), i + 6, euro(summary['bootstrapWorkflows'][i + 5]['costEur'])] for i in range(5)], '1fr, 1fr, 1fr, 1fr'),
    }
    (directory / 'tables.typ').write_text('// Generated by analyze.py from the archived campaign.\n' + '\n\n'.join('#let ' + k + ' = [\n' + v + '\n]' for k, v in entries.items()) + '\n')
    unsettled = [r for r in rows if not r['accountingComplete']]
    note = ''
    if unsettled:
        note = (' #super[a] Settled cost only. One provider request of this observation returned an HTTP error without usage, so its worst-case reservation of EUR '
                + ', '.join(euro(r['upperBoundCostEur'] - r['costEur']) for r in unsettled) + ' bounds the missing amount.')
    appendix = ['// Generated by analyze.py.\n#pagebreak()\n#heading(level: 2, numbering: none)[Individual Benchmark Observations] <sec:benchmark-observations>',
                f'@tab:observations-1 to @tab:observations-{6 + summary["bootstrapStages"] - 1} report all {summary["observations"]} observations from campaign `{summary["campaignId"]}`. Every observation completed. R denotes repetition, Req. provider requests, and Calls the tool calls issued by the model. Input includes cache reads and writes. Output includes reasoning tokens. Exec. measures the span from the first provider request to the last provider response, while Event measures the delay from the trigger to observed completion, both in seconds. Full token categories, tool counts, phase costs, and hashes accompany the thesis in the offline analysis artifacts.' + note]
    groups = [(c, 1, NAMES[c]) for c in CONDITIONS] + [('bootstrap', s, f'Bootstrap continuation {s - 1}') for s in stages]
    for index, (c, stage, title) in enumerate(groups):
        if index and index % 2 == 0:
            appendix.append('#pagebreak()')
        subset = sorted([r for r in rows if r['condition'] == c and r['stage'] == stage], key=lambda r: r['repetition'])
        identifier = f'rNN-{c}-s{stage}'
        appendix.append('#figure(\n[' + table(['R', 'Cost EUR', 'Input', 'Output', 'Req.', 'Calls', 'Exec.', 'Event'],
                                               [[r['repetition'], marked(r), number(r['inputTokens']), number(r['outputTokens']), r['providerRequests'], r['callbacks'],
                                                 f"{r['executionSeconds']:.2f}", f"{r['eventToCompletionSeconds']:.2f}"] for r in subset],
                                               '0.3fr, 0.95fr, 0.95fr, 0.75fr, 0.45fr, 0.5fr, 0.65fr, 0.65fr', '8pt')
                        + '],\nkind: table, caption: [' + title + '. Observation identifiers follow `' + identifier + '`, with NN denoting the two-digit repetition. Costs use frozen rates.],\n) <tab:observations-' + str(index + 1) + '>')
    (directory / 'observations-appendix.typ').write_text('\n\n'.join(appendix) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path, help='Archived campaign directory')
    parser.add_argument('--output', required=True, type=Path, help='Directory for derived outputs, outside the evidence')
    parser.add_argument('--write-checksums', action='store_true', help='Freeze checksums.json for a newly archived package, then exit')
    parser.add_argument('--no-figures', action='store_true', help='Run accounting using only the Python standard library.')
    args = parser.parse_args()
    evidence, out = args.evidence.resolve(), args.output.resolve()
    if args.write_checksums:
        require(not (evidence / CHECKSUMS).exists(), 'checksums.json already frozen')
        dump(evidence / CHECKSUMS, package_hashes(evidence))
        return
    require(not out.is_relative_to(evidence), 'Output must be outside the evidence package')
    try:
        rows, summary, audit = analyze(evidence)
        dump(out / 'audit.json', audit)
        dump(out / 'summary.json', summary)
        dump(out / 'observations.json', rows)
        with (out / 'observations.csv').open('w', newline='') as stream:
            flat = [{k: json.dumps(v, sort_keys=True, default=str) if isinstance(v, (dict, list)) else v for k, v in row.items()} for row in rows]
            writer = csv.DictWriter(stream, fieldnames=list(flat[0]), lineterminator='\n')
            writer.writeheader()
            writer.writerows(flat)
        typst_tables(rows, summary, out)
        if not args.no_figures:
            figures(rows, summary, out / 'figures')
        print(json.dumps({'campaign': summary['campaignId'], 'checks': len(audit['checks']), 'observations': len(rows), 'providerAttempts': summary['providerAttempts'],
                          'costEur': str(summary['totalCostEur']), 'upperBoundEur': str(summary['totalUpperBoundEur']), 'criterion': summary['criterion']}))
    except (AuditError, KeyError, ValueError, OSError, ArithmeticError, TypeError) as error:
        dump(out / 'audit.json', {'failedChecks': [str(error)], 'criterion': 'incomplete'})
        raise SystemExit('AUDIT FAILED: ' + str(error)) from error


if __name__ == '__main__':
    main()
