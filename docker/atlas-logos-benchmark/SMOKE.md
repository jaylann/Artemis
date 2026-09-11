# Current shared-budget smoke, 11 September 2026

Campaign `atlas-logos-smoke-20260911-tool-budget-v1` passed against a fresh compiled runtime with the shared 128 attempted-tool-callback limit. Both normal instructor-triggered exercise maintenance and automatic lecture maintenance completed with nested workers and persisted mapping changes. All 32 provider HTTP attempts reconciled with Logos, used Luna, and covered orchestrator xhigh, worker high, and flavor medium reasoning. No usage was missing; `smokeReady=true` and integrity issues are empty. AtlasML remained disabled.

| Case | Attempts | Execution | Event to completion | Cost EUR |
|---|---:|---:|---:|---:|
| Manual exercise | 12 | 42.307 s | | 0.0057572176 |
| Automatic lecture | 20 | 65.914 s | 71.519 s | 0.0084773601 |
| Total | 32 | | | 0.0142345778 |

Evidence: `build/atlas-logos-benchmark/evidence/atlas-logos-smoke-20260911-tool-budget-v1/report.json` and its accompanying immutable journals, fixtures, schedule, and Logos export. Runtime hashes match the formal deployment. Seventeen runner tests also passed before execution. Earlier focused application tests verify nested budget sharing and rejection of callback 129; the paid smoke validates integration, not exhaustive limit exhaustion or semantic quality.

This smoke used the remaining original EUR 1 allowance, preserving earlier charges and unresolved reservations. The separate formal campaign is `atlas-logos-formal-20260911-tool-budget-v1`; these two smoke observations are not formal results. Historical reports below retain the configuration and budget figures applicable at their execution time.

---

# Historical all-Luna recovery smoke, 10 September 2026

Both manual exercise and automatic lecture maintenance passed under the revised recovery profile. All 32 provider attempts used `gpt-5.6-luna`, with xhigh/high/medium reasoning for orchestration/workers/flavor stripping. Every request reconciled with Logos. No usage was missing. Workers executed, persisted mapping changes occurred, and the automatic accumulator/scheduler path completed. The report sets `smokeReady=true` with no integrity issues.

| Case | Attempts | Execution | Cost EUR |
|---|---:|---:|---:|
| Manual exercise | 12 | 42.911 s | 0.0051862170 |
| Automatic lecture | 20 | 51.494 s | 0.0066657570 |
| Total | 32 | | 0.0118519739 |

Evidence: `build/atlas-logos-benchmark/evidence/atlas-logos-smoke-20260910-all-luna-v2/`. This smoke used the remaining original EUR 1 allowance. Across all smoke attempts, measured cost plus unresolved held reservations remains below EUR 1. The old formal campaign's EUR 0.2042370494 measured and EUR 0.5031642636 held separately reduce the replacement formal cap to EUR 19.2925986869.

These observations validate the all-Luna execution and measurement path. They are not formal campaign results. Fresh formal courses and the new 80-observation schedule belong to `atlas-logos-formal-20260910-recovery-v2`. The earlier configurations below remain historical evidence.

---

# Passed Responses smoke — 2026-09-10

The current configuration passed both real Atlas smoke cases through local Logos. All 30 provider HTTP attempts reconcile with Logos, with no missing usage or evidence-integrity issues. The report sets `smokeReady=true`: exact model/reasoning coverage, nested workers, persisted mapping changes, and automatic triggering all passed. AtlasML remained disabled.

| Case | Attempts | Execution | Event to completion | Cost EUR |
|---|---:|---:|---:|---:|
| Manual exercise maintenance | 13 | 87.236 s | 87.446 s | 0.0272087882 |
| Automatic lecture maintenance | 17 | 83.465 s | 89.767 s | 0.0195230175 |
| Total | 30 | | | 0.0467318057 |

The exercise case changed persisted exercise links from 12 to 13; the lecture-triggered case changed exercise links from 12 to 16 while preserving its one lecture link. These are autonomous outcomes, not required semantic judgments. Both cases completed with all provider usage measured, including Luna cache writes. The automatic path used the existing accumulator and scheduler.

Across all attempts, measured cost is EUR 0.0639357192 and earlier unresolved reservations remain EUR 0.2690058359. Their sum, EUR 0.3329415551, remains below the original EUR 1 allowance. Old failed observations and their unmeasured costs are preserved separately; their reservations were never released or reset.

Current evidence: `build/atlas-logos-benchmark/evidence/atlas-logos-smoke-responses-20260910-v3/`, `responses-smoke-report-v3.json`, `smoke-allowance-summary.json`, and `course-isolation-after-responses-smoke.json`. All ten local fixture courses have automation disabled. Artemis and the isolated Compose services were stopped after capture; volumes and evidence remain.

The formal campaign is unrun. These two smoke observations verify the measurement path, not the six-condition experiment or general affordability. Freeze a new formal manifest against the final source/pricing before formal execution.

---

# Historical smoke attempts — 2026-09-10

## Outcome

The official pinned Logos stack built and started, and Artemis started against the isolated PostgreSQL database. Both exact model identifiers passed the read-only OpenAI and Logos inventory checks. The guarded smoke then failed at the orchestrator call:

> Function tools with reasoning_effort are not supported for gpt-5.6-luna in /v1/chat/completions.

The provider error directs callers to Responses or reasoning `none`. Neither change was made: this experiment requires the normal Chat Completions tool loop with Luna/xhigh and Luna/high. The current combination cannot complete an orchestration. Worker execution, model reasoning compatibility, persisted autonomous mapping changes, and successful automatic completion remain unverified. The formal campaign is unrun and not ready.

## Captured attempts and costs

| Phase | Actual model | HTTP | Input / cached / output tokens | Cost EUR |
|---|---|---|---|---|
| Flavor stripping | gpt-5.4-mini-2026-03-17 | 200 | 3050 / 0 / 1888 | 0.0092546344 |
| Orchestration | No returned identity; requested gpt-5.6-luna | 400 | Missing usage | Unknown |

Both requests traversed Logos and match its exported request identifiers. The successful attempt's usage reconciles exactly. The rejected request has no returned model or usage, so total cost is unknown rather than zero. Its conservative EUR 0.1345096121 reservation remains held; measured cost plus that reservation is EUR 0.1437642465, below the EUR 1 allowance. No reservation was reset or reused.

The automatic lecture update reached the normal scheduler, but the dispatch guard blocked its provider call because the preceding attempt's usage was incomplete. Both scheduled observations remain failed. `smokeReady` is false. All six setup/smoke courses were verified to have automation disabled afterward, and a stop signal prevents further dispatch.

## Runtime fixes and retained evidence

Two fixture-setup attempts failed before any provider invocation because a custom lecture channel name exceeded Artemis's allowed length. Their courses and zero-dispatch evidence remain preserved. The runner now omits that optional name and lets Artemis generate it; live preparation also returns a nonzero exit on a setup blocker. Nine runner tests passed after these changes. Logos optional calibration is disabled.

Evidence is retained under `build/atlas-logos-benchmark/`:

- `evidence/atlas-logos-smoke-20260910-v3/`: frozen definition, fixtures, observations, HTTP/invocation events, durable dispatch reservations, and `logos.jsonl`.
- `smoke-report-v3.json`: complete reconciled report, including failed observations.
- `course-isolation-after-smoke.json`: automation disabled for every local course.
- `logos-before-smoke.jsonl`: empty pre-smoke provider ledger.
- `logos-build-resumed.log`, `logos-provision.log`, and `artemis-smoke-server-v3.log`: runtime evidence.
- Earlier smoke setup directories retain their failure markers and empty provider ledgers.

The formal manifest predates these runtime fixes. Before eventual formal execution, freeze preparation again against the final compatible configuration; do not use the old manifest as evidence for changed code. No formal observation was dispatched or replaced.

Artemis and the isolated Compose services were stopped after evidence capture. Images, volumes, credentials, and evidence remain available for a later compatible run.

## Responses validation, second smoke configuration

The first Responses preflight made no provider calls: deferred Spring initialization exposed ambiguous constructor binding in the benchmark-only properties record. An explicit canonical constructor binding and two passing ApplicationContextRunner tests correct this.

The next configuration (`atlas-logos-smoke-responses-20260910-v2`) reached OpenAI through Logos. Mini flavor stripping returned HTTP 200 (3050 input, zero cached, 1550 output; EUR 0.0079492791). Luna/xhigh also returned HTTP 200, confirming transport access. Its returned SDK model union was decoded incorrectly by the adapter, so no model-selected tool ran. All three model-union variants now have passing regression tests in both worktrees.

Logos recorded Luna usage of 2902 input, 392 output, 287 reasoning (included in output), and 2899 cache-write tokens. The original ledger deliberately rejected cache writes as outside its price scope; that attempt remains unmeasured in its immutable record, with EUR 0.1344962238 held. OpenAI's documented 1.25-times input cache-write rate is being added to future accounting and dispatch bounds. The automatic lecture case reached the scheduler but dispatch was halted after incomplete usage. Neither failed observation is replaced.

Both smoke configurations together have EUR 0.0172039135 measured plus EUR 0.2690058359 held, leaving EUR 0.7137902506 under the original allowance. Evidence and `responses-smoke-report-v2.json` are preserved under the ignored benchmark build directory. No formal observations ran.

## Graceful revision, 11 September 2026

The `atlas-logos-smoke-20260911-graceful-v2` rehearsal completed all eight planned observations (six conditions and both bootstrap continuations). All 286 provider attempts reconciled with Logos; captured total cost was EUR 0.1016399158942670786131136286, with no missing usage. Luna was observed at xhigh/high/medium for orchestrator/worker/flavor stripping. Both trigger paths, nested workers, persisted mapping changes, and final course isolation passed. Callback counts were 40, 51, 96, 92, 16, 91, 52, and 17.

No live observation exhausted the hard cap or had work rejected during wrap-up. Those paths passed deterministic native-advisor tests; this rehearsal does not prove how every live model invocation will react at exhaustion. The develop-based Responses PR uses flat tools and has not been deployed independently.

The first graceful rehearsal stopped before provider dispatch due to an incorrect configuration read-back endpoint. Its infrastructure-failure record and operator cleanup are preserved separately. The replacement froze the runner correction before any observations, with no allowance reset.

Local evidence: `build/atlas-logos-benchmark/evidence/atlas-logos-smoke-20260911-graceful-v2/report.json`, `readiness-summary.json`, and `automation-readback-preflight.json`. Runtime and automated-check details are in `build/atlas-logos-benchmark/GRACEFUL-VERIFICATION.md`. Formal observations remain a separate dataset.
