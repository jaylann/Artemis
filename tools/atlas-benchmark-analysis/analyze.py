#!/usr/bin/env python3
"""Recompute archived Atlas provider costs offline. Never imports the live runner.

Outputs are derived artifacts. Evidence and frozen source snapshots are read-only.
The supplied complete campaigns are checked strictly; damaged or unsupported records
produce an audit failure, never a silently reduced sample or a zero usage estimate.
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
TOKENS = ['inputTokens','cachedInputTokens','cacheWriteInputTokens','uncachedInputTokens','outputTokens','reasoningTokens']
READS = {'getExerciseContent','getLectureUnitContent','getCompetencyDetails','listCompetencyIndex'}
WRITES = {'assignExerciseToCompetency','assignLectureUnitToCompetency','unassignExerciseFromCompetency','unassignLectureUnitFromCompetency','createCompetency','editCompetency'}
DELEGATIONS = {'delegateToAssigner','delegateToCreator','delegateToEditor'}
TERMINALS = {'completeOrchestration','completeWorkerTask'}
EFFORTS = {'flavor_strip':'medium','orchestration':'xhigh','worker':'high'}

class AuditError(ValueError):
    """Evidence cannot support the requested published calculation."""

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, default=str) + '\n')

def statistics(values):
    return dict(minimum=min(values), median=median(values), maximum=max(values), total=sum(values))

def require(ok, message):
    if not ok:
        raise AuditError(message)

def verify_package(evidence):
    expected=json.loads((evidence/'checksums.json').read_text())
    actual={str(p.relative_to(evidence)):sha(p) for p in evidence.rglob('*') if p.is_file() and p.name!='checksums.json'}
    require(actual==expected,'Package checksum mismatch: '+', '.join(k for k in actual.keys()|expected.keys() if actual.get(k)!=expected.get(k)))
    return expected

def attempt_cost(a, pricing, gateway):
    r=pricing['models'].get(a.get('returnedModel'))
    require(r is not None and a.get('requestedModel')==a.get('returnedModel'), 'Unsupported model: '+a.get('eventId','unknown'))
    values=[a.get(k) for k in ['inputTokens','cachedInputTokens','cacheWriteInputTokens','outputTokens','reasoningTokens']]
    require(a.get('usageAvailable') is True and all(type(v) is int and v>=0 for v in values),'Missing or invalid usage: '+a['eventId'])
    i,c,w,o,reasoning=values
    require(c+w<=i and reasoning<=o and i<=r['maxSupportedInputTokens'] and o<=r['maxOutputTokens'],'Usage outside supported pricing: '+a['eventId'])
    require(a.get('reasoningEffort')==EFFORTS.get(a.get('phase')),'Unsupported reasoning configuration: '+a['eventId'])
    g=gateway.get(a.get('logosRequestId'))
    require(g is not None and g['model']==a['requestedModel'] and g['status']=='success' and a['httpStatus']==200 and a['status']=='completed','Unreconciled provider status/identity: '+a['eventId'])
    # An omitted zero-valued gateway category is licensed only by captured HTTP zero.
    require(all(g['usage'].get(k,0 if v==0 else None)==v for k,v in zip(['prompt_tokens','prompt_cached_tokens','prompt_cache_write_tokens','completion_tokens'],[i,c,w,o])),'Gateway usage mismatch: '+a['eventId'])
    try:
        rates=[D(r[k]) for k in ['inputUsdPerMillion','cachedInputUsdPerMillion','cacheWriteInputUsdPerMillion','outputUsdPerMillion']]
        fx=D(pricing['usdPerEur'])
    except (KeyError, ArithmeticError, TypeError) as error:
        raise AuditError('Unsupported frozen pricing: '+str(error)) from error
    require(fx.is_finite() and fx>0 and all(v.is_finite() and v>=0 for v in rates),'Invalid frozen price or exchange rate')
    if i>272000:
        tier=r.get('longContext')
        require(tier is not None and tier['thresholdTokens']==272000,'Unsupported long-context pricing')
        rates=[v*D(str(tier['inputMultiplier'])) for v in rates[:3]]+[rates[3]*D(str(tier['outputMultiplier']))]
    return sum(D(v)*rate for v,rate in zip([i-c-w,c,w,o],rates))/D(1_000_000)/fx

def analyze(evidence):
    hashes=verify_package(evidence)
    j=lambda n:json.loads((evidence/n).read_text())
    jl=lambda n:[json.loads(line) for line in (evidence/n).read_text().splitlines()]
    manifest,pricing=j('manifest.json'),j('pricing.json')
    schedule,fixtures=j('schedule.json')['schedule'],j('fixtures.json')['courses']
    provider,gateway=jl('provider-events.jsonl'),jl('logos.jsonl')
    terminals,lifecycle=jl('invocation-events.jsonl'),jl('observations.jsonl')
    journal,report=jl('dispatch-journal.jsonl'),j('report.json')
    campaign=manifest['campaignId']; stages=manifest.get('bootstrapStages',3); reps=manifest['repetitions']
    checks=[]
    def check(name,ok):
        checks.append({'check':name,'passed':bool(ok)})
        require(ok,name)
    check('unique complete schedule',len(schedule)==manifest['observationCount']==len({x['observationId'] for x in schedule})==len({x['invocationId'] for x in schedule}))
    check('six primary conditions repeated ten times',reps==10 and Counter(x['condition'] for x in schedule if x['stage']==1)==Counter({c:reps for c in CONDITIONS}))
    for rep in range(1,reps+1):
        order=list(CONDITIONS);random.Random(manifest['seed']+rep*1_000_003).shuffle(order)
        check(f'repetition {rep}: seeded order and complete bootstrap',order==[x['condition'] for x in schedule if x['stage']==1 and x['repetition']==rep] and sorted(x['stage'] for x in schedule if x['courseKey']==f'r{rep:02}-bootstrap')==list(range(1,stages+1)))
    check('60 isolated fixture courses',len(fixtures)==len({x['courseId'] for x in fixtures.values()})==60)
    check('disjoint exercise identities',len({i for x in fixtures.values() for i in x['exerciseIds']})==3600)
    check('frozen hashes',sha(evidence/'pricing.json')==manifest['pricingSha256'] and digest(schedule)==manifest['scheduleSha256'] and digest(j('fixture-definition.json'))==manifest['fixtureSha256'] and digest(manifest)==j('schedule.json')['manifestSha256'])
    sources=j('source-sha256.json')
    check('frozen source bytes and composite fingerprint',all(sha(evidence/'source'/p)==h for p,h in sources.items()) and digest(sources)==manifest['codeSha256'])
    check('256 shared callbacks with reserve',manifest['toolBudget']=={'finalSlotForCompletion':True,'sharedCallbacks':256,'wrapUpAt':224})
    check('unique gateway and attempt identities',len(gateway)==len({x['requestId'] for x in gateway}) and len(provider)==len({x['eventId'] for x in provider})==len({x['logosRequestId'] for x in provider}))
    check('continuous telemetry sequence',sorted(x['sequence'] for x in provider+terminals)==list(range(1,len(provider)+len(terminals)+1)))
    exports={x['requestId']:x for x in gateway}
    costs={a['eventId']:attempt_cost(a,pricing,exports) for a in provider}
    check('every attempt reconciled',len(costs)==len(provider))
    reserved=Counter(x['attemptId'] for x in journal if x['type']=='reserve');settled=Counter(x['attemptId'] for x in journal if x['type']=='settle')
    check('one reservation and settlement per attempt',reserved==settled==Counter({a['eventId']:1 for a in provider}) and all(x['type'] in ['reserve','settle'] for x in journal))
    byobs,terminal_byobs,life_byobs=defaultdict(list),defaultdict(list),defaultdict(list)
    for a in provider:byobs[a['observationId']].append(a)
    for t in terminals:terminal_byobs[t['observationId']].append(t)
    for l in lifecycle:life_byobs[l['observationId']].append(l)
    ids={p['observationId'] for p in schedule}
    check('no unscheduled observations or events',set(byobs)==set(terminal_byobs)==set(life_byobs)==ids and all(a['campaignId']==campaign for a in provider+terminals))
    chronological=[terminal_byobs[x['observationId']][0] for x in schedule]
    check('sequential invocations',all(datetime.fromisoformat(a['endedAt'].replace('Z','+00:00'))<=datetime.fromisoformat(b['startedAt'].replace('Z','+00:00')) for a,b in zip(chronological,chronological[1:])))
    report_rows={x['observationId']:x for x in report['observations']};rows=[];tool_totals=Counter()
    check('complete report identities',len(report['observations'])==len(report_rows)==len(ids) and set(report_rows)==ids)
    for plan in schedule:
        oid=plan['observationId'];ts,ls,attempts=terminal_byobs[oid],life_byobs[oid],byobs[oid]
        check(f'{oid}: single terminal and lifecycle',len(ts)==1 and [x['eventType'] for x in ls]==['schedule_started','schedule_terminal'])
        t,l=ts[0],ls[-1];record=fixtures[plan['courseKey']]
        valid=t['terminalStatus']==l['status']=='completed' and t['usageComplete'] and not l['error'] and not l['cleanupError']
        valid=valid and t['invocationId']==plan['invocationId'] and len(t['providerAttemptIds'])==len(set(t['providerAttemptIds'])) and set(t['providerAttemptIds'])=={a['eventId'] for a in attempts}
        valid=valid and all(a['invocationId']==plan['invocationId'] and a['condition']==plan['condition'] and int(a['stage'])==plan['stage'] and a['repetition']==plan['repetition'] for a in attempts)
        valid=valid and all(l[s]['courseId']==record['courseId'] and l[s]['exerciseCount']==60 for s in ['beforeState','afterState'])
        previous=life_byobs[plan['requiresObservationId']][-1]['afterState'] if plan['requiresObservationId'] else record['initialState']
        valid=valid and previous==l['beforeState']
        activity=t['details']['toolActivity'];counts=Counter(x['tool'] for x in activity)
        valid=valid and t['toolCallCount']==len(activity) and all(x['outcome']=='completed' for x in activity) and not t['details']['hardLimitReached'] and not t['details']['workBlocked']
        check(f'{oid}: completion, state and tool trajectory',valid and report_rows[oid]['classification']=='valid-completed')
        check(f'{oid}: recognized tools',set(counts)<=READS|WRITES|DELEGATIONS|TERMINALS);tool_totals.update(counts)
        row={k:plan[k] for k in ['observationId','invocationId','courseKey','condition','repetition','stage','trigger','scheduleIndex']}
        row.update(classification='valid-completed',costEur=sum((costs[a['eventId']] for a in attempts),D(0)),providerRequests=len(attempts),retries=sum((a.get('sdkRetryCount') or 0)>0 for a in attempts),callbacks=t['toolCallCount'],reads=sum(counts[k] for k in READS),writes=sum(counts[k] for k in WRITES),delegations=sum(counts[k] for k in DELEGATIONS),completionCallbacks=sum(counts[k] for k in TERMINALS),executionSeconds=D(t['durationMs'])/1000,eventToCompletionSeconds=D(l['eventToCompletionMs'])/1000,beforeState=l['beforeState'],afterState=l['afterState'],tools=dict(counts),changedObjectTypes=plan.get('changedObjectTypes',[]),changedObjectKeys=plan.get('changedObjectKeys',[]))
        row['overheadSeconds']=row['eventToCompletionSeconds']-row['executionSeconds']
        for k in TOKENS:row[k]=sum(a[k] for a in attempts) if k!='uncachedInputTokens' else sum(a['inputTokens']-a['cachedInputTokens']-a['cacheWriteInputTokens'] for a in attempts)
        row['phaseCostsEur']={p:sum((costs[a['eventId']] for a in attempts if a['phase']==p),D(0)) for p in EFFORTS}
        check(f'{oid}: reproduced cost',abs(row['costEur']-D(report_rows[oid]['costEur']))<D('1e-25'))
        rows.append(row)
    metrics=['costEur',*TOKENS,'providerRequests','retries','callbacks','reads','writes','delegations','completionCallbacks','executionSeconds','eventToCompletionSeconds','overheadSeconds']
    aggregate=lambda data:{'n':len(data),**{k:statistics([x[k] for x in data]) for k in metrics}}
    primary={c:aggregate([r for r in rows if r['condition']==c and r['stage']==1]) for c in CONDITIONS}
    continuations={str(s):aggregate([r for r in rows if r['stage']==s]) for s in range(2,stages+1)}
    workflows=[{'courseKey':f'r{rep:02}-bootstrap','repetition':rep,'costEur':sum(r['costEur'] for r in rows if r['courseKey']==f'r{rep:02}-bootstrap'),'stages':[r['observationId'] for r in rows if r['courseKey']==f'r{rep:02}-bootstrap']} for rep in range(1,reps+1)]
    total=sum(r['costEur'] for r in rows);phases={}
    for phase in EFFORTS:
        attempts=[a for a in provider if a['phase']==phase];cost=sum(costs[a['eventId']] for a in attempts)
        phases[phase]={'requests':len(attempts),'requestSharePercent':D(len(attempts))/len(provider)*100,'costEur':cost,'costSharePercent':cost/total*100,**{k:sum(a[k] for a in attempts) for k in TOKENS if k!='uncachedInputTokens'}}
    maximum=max(rows,key=lambda r:r['costEur'])
    summary={'campaignId':campaign,'observations':len(rows),'primaryCount':60,'continuationCount':len(rows)-60,'bootstrapStages':stages,'providerAttempts':len(provider),'totalCostEur':total,'primaryCostEur':sum(r['costEur'] for r in rows if r['stage']==1),'continuationCostEur':sum(r['costEur'] for r in rows if r['stage']>1),'maximumCostEur':maximum['costEur'],'maximumObservationId':maximum['observationId'],'criterionEur':D('.50'),'marginEur':D('.50')-maximum['costEur'],'criterion':'pass' if maximum['costEur']<=D('.50') else 'fail','primary':primary,'continuations':continuations,'bootstrapWorkflows':workflows,'bootstrapWorkflowCosts':statistics([x['costEur'] for x in workflows]),'phases':phases,'allObservations':aggregate(rows),'toolTotals':dict(tool_totals),'maximumAttemptInputTokens':max(a['inputTokens'] for a in provider),'longContextAttempts':sum(a['inputTokens']>272000 for a in provider),'overheadByTrigger':{t:statistics([r['overheadSeconds'] for r in rows if r['trigger']==t]) for t in ['automatic','manual']},'operationalPauses':j('operational-pauses.json') if (evidence/'operational-pauses.json').exists() else [],'typeCoverage':{t:{'targetedObjectOccurrences':sum(r['changedObjectTypes'].count(t) for r in rows),'observations':sum(t in r['changedObjectTypes'] for r in rows)} for t in sorted({t for r in rows for t in r['changedObjectTypes']})}}
    verify_package(evidence)
    audit={'campaignId':campaign,'checks':checks,'failedChecks':[],'packageFilesVerified':len(hashes),'sourceFilesVerified':len(sources),'runtimeVerification':'Recorded hashes retained; runtime binaries are not needed or claimed verified by this offline package analysis.','maximumReportRoundingDifferenceEur':max(abs(r['costEur']-D(report_rows[r['observationId']]['costEur'])) for r in rows),'accounting':'Decimal precision 28. Apply frozen USD rates then divide by frozen USD per EUR; round only for display.'}
    return rows,summary,audit

def figures(rows, summary, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FormatStrFormatter
    matplotlib.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "svg.fonttype": "none", "svg.hashsalt": summary["campaignId"], "axes.spines.top": False, "axes.spines.right": False})
    out.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(5.85, 3.25), layout="constrained")
    for i, c in enumerate(CONDITIONS):
        values = [r for r in rows if r["condition"] == c and r["stage"] == 1]
        xs = [float(r["costEur"]) for r in values]
        ax.hlines(i, min(xs), max(xs), color="#777777", linewidth=1)
        ax.scatter(xs, [i + (r["repetition"] - 5.5) * .026 for r in values], s=22, color="#256080", alpha=.85, zorder=3)
        ax.plot([float(median(xs))], [i], "|", markersize=17, markeredgewidth=2, color="#111111", zorder=4)
    ax.set_yticks(range(6), [NAMES[c] for c in CONDITIONS]); ax.invert_yaxis()
    ax.set_xlabel("Provider cost per invocation (EUR)"); ax.set_xlim(0, .027)
    ax.set_xticks([0, .005, .010, .015, .020, .025])
    ax.xaxis.set_major_formatter(FormatStrFormatter("%.6f")); ax.grid(axis="x", alpha=.2)
    fig.savefig(out / "primary-costs.svg", metadata={"Date": None}); plt.close(fig)
    fig, ax = plt.subplots(figsize=(5.85, 3.0), layout="constrained")
    for rep in range(1, 11):
        vals = sorted([r for r in rows if r["condition"] == "bootstrap" and r["repetition"] == rep], key=lambda r:r["stage"])
        ax.plot(list(range(1,summary["bootstrapStages"]+1)), [float(r["costEur"]) for r in vals], color="#b5c5ce", lw=.85, marker="o", markersize=3)
    ax.plot(list(range(1,summary["bootstrapStages"]+1)), [float(median(r["costEur"] for r in rows if r["condition"] == "bootstrap" and r["stage"] == s)) for s in range(1,summary["bootstrapStages"]+1)], color="#1e5674", lw=2, marker="D", markersize=5, label="Stage median")
    ax.set_xticks(list(range(1,summary["bootstrapStages"]+1)),["Primary\n12 objects", "Continuation 1\n24 objects", "Continuation 2\n24 objects", "Continuation 3\n12 objects"][:summary["bootstrapStages"]])
    ax.set_ylabel("Provider cost per invocation (EUR)"); ax.set_ylim(0,.050); ax.yaxis.set_major_formatter(FormatStrFormatter("%.6f"))
    ax.grid(axis="y", alpha=.2); ax.legend(frameon=False, loc="upper left")
    fig.savefig(out / "bootstrap-stages.svg", metadata={"Date": None}); plt.close(fig)


def typst_tables(rows, summary, directory):
    def cell(x):
        return "[" + str(x) + "]"
    def table(headers, values, widths=None, size="8.5pt"):
        cols = widths or ", ".join(["1fr"] * len(headers))
        return "#text(size: " + size + ")[#set par(leading: 0.55em, first-line-indent: 0pt)\n#table(columns: (" + cols + "), inset: 3pt, stroke: 0.4pt, align: left, table.header(" + ", ".join(cell("*"+h+"*") for h in headers) + "),\n" + "\n".join(", ".join(cell(x) for x in row) + "," for row in values) + "\n)]"
    euro = lambda v: f"{D(v):.6f}"
    number = lambda v: f"{v:,}"
    tokenfmt = lambda v: f"{int(v):,}" if v == int(v) else f"{v:,.1f}"
    rangefmt = lambda s: f"{tokenfmt(s['median'])} ({tokenfmt(s['minimum'])} to {tokenfmt(s['maximum'])})"
    seconds = lambda s: f"{s['median']:.2f} ({s['minimum']:.2f} to {s['maximum']:.2f})"
    entries = {}
    entries["primary-cost"] = table(["Condition", "n", "Median EUR", "Min. EUR", "Max. EUR"], [[NAMES[c],10,*[euro(summary['primary'][c]['costEur'][k]) for k in ['median','minimum','maximum']]] for c in CONDITIONS], "1.5fr, 0.3fr, 1fr, 1fr, 1fr")
    entries["primary-operations"] = table(["Condition", "Requests", "Callbacks", "Execution (s)"], [[NAMES[c],rangefmt(summary['primary'][c]['providerRequests']), rangefmt(summary['primary'][c]['callbacks']), seconds(summary['primary'][c]['executionSeconds'])] for c in CONDITIONS], "1.1fr, 1fr, 1fr, 1.6fr")
    entries["primary-tokens"] = table(["Condition", "Input tokens", "Output tokens"], [[NAMES[c], rangefmt(summary['primary'][c]['inputTokens']),rangefmt(summary['primary'][c]['outputTokens'])] for c in CONDITIONS], "1fr, 1.8fr, 1.65fr")
    entries["phases"] = table(["Phase", "Requests", "Cost EUR", "Cost share"], [[{'flavor_strip':'Flavor stripping','orchestration':'Orchestrator','worker':'Workers'}[p], s['requests'], euro(s['costEur']),f"{s['costSharePercent']:.1f}%"] for p,s in summary['phases'].items()], "1.5fr, 0.8fr, 1.1fr, 1fr")
    entries["continuations"] = table(["Stage", "n", "Median EUR", "Min. EUR", "Max. EUR"], [[f"Continuation {s-1}",10,*[euro(summary['continuations'][str(s)]['costEur'][k]) for k in ['median','minimum','maximum']]] for s in range(2,summary["bootstrapStages"]+1)], "1.5fr, 0.3fr, 1fr, 1fr, 1fr")
    entries["continuation-operations"] = table(["Stage", "Requests", "Callbacks", "Execution (s)"], [[f"Continuation {s-1}",rangefmt(summary['continuations'][str(s)]['providerRequests']), rangefmt(summary['continuations'][str(s)]['callbacks']), seconds(summary['continuations'][str(s)]['executionSeconds'])] for s in range(2,summary["bootstrapStages"]+1)], "1.1fr, 1fr, 1fr, 1.6fr")
    entries["type-coverage"] = table(["Target object type", "Observations", "Target occurrences"], [[k.replace(":", " / "),v["observations"],v["targetedObjectOccurrences"]] for k,v in summary["typeCoverage"].items()], "2fr, 1fr, 1fr")
    entries["workflows"] = table(["Repetition", "Workflow EUR", "Repetition", "Workflow EUR"], [[i+1,euro(summary['bootstrapWorkflows'][i]['costEur']),i+6,euro(summary['bootstrapWorkflows'][i+5]['costEur'])] for i in range(5)], "1fr, 1fr, 1fr, 1fr")
    (directory / 'tables.typ').write_text('// Generated by analyze.py from the frozen campaign.\n' + '\n\n'.join('#let '+k+' = [\n'+v+'\n]' for k,v in entries.items()) + '\n')
    appendix = ['// Generated by analyze.py.\n#pagebreak()\n#heading(level: 2, numbering: none)[Individual Benchmark Observations] <sec:benchmark-observations>', f'@tab:observations-1 to @tab:observations-{6+summary["bootstrapStages"]-1} report all {summary["observations"]} observations from campaign `{summary["campaignId"]}`. Every row is valid completed. R denotes repetition, Req. provider requests, and Calls attempted tool callbacks. Input includes cache reads and writes. Output includes reasoning tokens. Exec. measures orchestration execution, while Event measures the recorded trigger-to-observation delay, both in seconds. Full token categories, tool counts, phase costs, and source hashes accompany the thesis in the offline analysis artifacts.']
    groups = [(c,1,NAMES[c]) for c in CONDITIONS] + [('bootstrap',s,f'Bootstrap continuation {s-1}') for s in range(2,summary["bootstrapStages"]+1)]
    for index,(c,stage,title) in enumerate(groups):
        if index and index%2 == 0:
            appendix.append('#pagebreak()')
        subset=sorted([r for r in rows if r['condition']==c and r['stage']==stage],key=lambda r:r['repetition'])
        identifier=f'rNN-{c}-s{stage}'
        appendix.append('#figure(\n['+table(['R','Cost EUR','Input','Output','Req.','Calls','Exec.','Event'], [[r['repetition'], euro(r['costEur']),number(r['inputTokens']),number(r['outputTokens']),r['providerRequests'],r['callbacks'],f"{r['executionSeconds']:.2f}",f"{r['eventToCompletionSeconds']:.2f}"] for r in subset], '0.3fr, 0.95fr, 0.95fr, 0.75fr, 0.45fr, 0.5fr, 0.65fr, 0.65fr', '8pt')+'],\nkind: table, caption: ['+title+'. Observation identifiers follow `'+identifier+'`, with NN denoting the two-digit repetition. Costs use frozen rates.],\n) <tab:observations-'+str(index+1)+'>')
    (directory / 'observations-appendix.typ').write_text('\n\n'.join(appendix)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',required=True,type=Path)
    parser.add_argument('--baseline',type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--no-figures',action='store_true',help='Run accounting using only the Python standard library.')
    args=parser.parse_args();out=args.output.resolve()
    require(not out.is_relative_to(args.evidence.resolve()) and not (args.baseline and out.is_relative_to(args.baseline.resolve())),'Output must be outside the evidence packages')
    try:
        rows,summary,audit=analyze(args.evidence.resolve())
        comparison=None
        if args.baseline:
            old_rows,old,old_audit=analyze(args.baseline.resolve())
            comparison={'baselineCampaign':old['campaignId'],'primaryCampaign':summary['campaignId'],'interpretation':'Descriptive comparison. Fixture text, object types, object count, bootstrap stages, cache state, pricing date and trajectories differ. No causal type effect is isolated.','baseline':{k:old[k] for k in ['observations','providerAttempts','totalCostEur','maximumCostEur','bootstrapWorkflowCosts']},'primary':{k:summary[k] for k in ['observations','providerAttempts','totalCostEur','maximumCostEur','bootstrapWorkflowCosts']},'conditions':{c:{'baselineMedianEur':old['primary'][c]['costEur']['median'],'primaryMedianEur':summary['primary'][c]['costEur']['median'],'changePercent':(summary['primary'][c]['costEur']['median']/old['primary'][c]['costEur']['median']-1)*100} for c in CONDITIONS}}
            dump(out/'baseline-audit.json',old_audit)
            dump(out/'comparison.json',comparison)
        dump(out/'audit.json',audit);dump(out/'summary.json',summary);dump(out/'observations.json',rows)
        with (out/'observations.csv').open('w',newline='') as stream:
            flat=[{k:json.dumps(v,sort_keys=True,default=str) if isinstance(v,(dict,list)) else v for k,v in row.items()} for row in rows]
            writer=csv.DictWriter(stream,fieldnames=list(flat[0]),lineterminator='\n');writer.writeheader();writer.writerows(flat)
        typst_tables(rows,summary,out)
        if comparison:
            table=['#let comparison-cost = [#text(size: 8.5pt)[#table(columns: (1.5fr, 1fr, 1fr, 0.8fr), inset: 3pt, stroke: 0.4pt, table.header([*Condition*], [*Text-only EUR*], [*Heterogeneous EUR*], [*Change*]),']
            for c,v in comparison['conditions'].items():table.append(f'[{NAMES[c]}], [{v["baselineMedianEur"]:.6f}], [{v["primaryMedianEur"]:.6f}], [{v["changePercent"]:+.1f}%],')
            table.append(')]]')
            with (out/'tables.typ').open('a') as stream:stream.write('\n'+'\n'.join(table)+'\n')
        if not args.no_figures:figures(rows,summary,out/'figures')
        print(json.dumps({'campaign':summary['campaignId'],'checks':len(audit['checks']),'observations':len(rows),'providerAttempts':summary['providerAttempts'],'costEur':str(summary['totalCostEur']),'criterion':summary['criterion']}))
    except (AuditError,KeyError,ValueError,OSError,ArithmeticError,TypeError) as error:
        dump(out/'audit.json',{'failedChecks':[str(error)],'criterion':'incomplete'})
        raise SystemExit('AUDIT FAILED: '+str(error)) from error

if __name__=='__main__':
    main()
