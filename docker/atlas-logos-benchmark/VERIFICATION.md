# Preparation verification — 2026-09-10

## Source and scope

This detached worktree starts at fetched develop `3422402470fe63e055f7f7246ad6b8547a99fb9e`, reconciled with the original checkout's committed and uncommitted application changes. No commits, merges, rebases, or pushes were created. All changes are unstaged or new files. Original HEAD, index, staged/unstaged patches, and 37 original untracked application files matched the preserved source snapshot.

The normal Spring AI tool loop remains the execution path. The old temporary Responses adapter and old benchmark endpoints/loop were omitted at initial reconciliation. The later Atlas-scoped Responses adapter is documented below. Benchmark-specific code, configuration, fixture, report template, and instructions are inside Artemis. The optional Logos stack uses official revision `0d4a6683171cd956e1cf46a19d49e99181a52ac4`; an existing Logos instance can supply its own URL and key.

## Completed offline checks

- Java production and test compilation passed with Java 25.
- Focused Atlas/telemetry lane: 216 tests passed, no failures or skips. After nullability reconciliation, the affected extraction lane passed all 33 tests; DTO contract tests also passed in the correction lane.
- Python runner/report lane: 9 tests passed, including cached-token accounting, retry costs, unresolved dispatches, immutable preparation, interrupted observations, and malformed evidence.
- Ruff and shell syntax checks passed.
- Atlas and core architecture checks passed across the architecture lane and targeted DTO naming correction run. One pre-existing disabled autowiring rule remains skipped (`integrationTestsShouldNotAutowireMembers`).
- Full Checkstyle main/test checks passed after reconciling newest develop nullability conventions. Changed Java files were formatted with Spotless. Final correction logs are `final-corrections.log` (DTO architecture/contracts passed; obsolete null-message assertion failed) and `final-extraction-check.log` (33/33 extraction tests passed after updating that assertion).
- The revised thesis compiled to 70 pages; affected evaluation/design pages were rendered and inspected. Results remain pending.
- Offline formal preparation produced 80 pending observations and zero provider attempts. `formal-results.csv` is an empty reporting template, not measured evidence.

Backend verification uses `./gradlew ... -x webapp` to avoid an unrelated web application build. The task's external build directory must end in `/build`: ArchUnit's default test importer otherwise finds no test classes. The initial nonstandard external directory caused eight initialization failures, not architecture-rule violations.

Commands for a normal checkout:

```sh
python3 -m unittest discover -s scripts/atlas-logos-benchmark -p 'test_*.py'
bash -n scripts/run-atlas-logos-benchmark.sh scripts/atlas-logos-benchmark/stack.sh
./gradlew -x webapp checkstyleMain checkstyleTest spotlessCheck
./gradlew -x webapp test --tests 'de.tum.cit.aet.artemis.atlas.architecture.*' --tests 'de.tum.cit.aet.artemis.core.architecture.CoreCodeStyleArchitectureTest' --tests 'de.tum.cit.aet.artemis.core.benchmark.AtlasLogosBenchmarkTest'
```

Task-local logs and source reconciliation evidence remain under `build/atlas-logos-benchmark/` (ignored runtime data). The portable branch consists of the source/config/scripts/docs; credentials, cloned Logos source, databases, and runtime evidence must remain outside Git.

## Live follow-up

The disk blocker was resolved and the official stack started. The preserved live smoke failed because OpenAI rejected Luna function tools with reasoning on Chat Completions. The Atlas-only Responses adapter has since been implemented; its provider compatibility was verified by the subsequent passed smoke described below. See [SMOKE.md](SMOKE.md) for the two attempts, reconciled successful cost, unresolved reservation, runtime fixes, and remaining coverage. Formal execution remains unrun.

## Responses adapter verification (2026-09-10)

The changed transport was checked independently of the earlier baseline above. Across the focused lane and targeted correction runs, all 62 tests passed: 31 orchestration, 7 delegation, 14 HTTP ledger/budget, 7 architecture, and 3 adapter tests. The adapter tests exercise the actual Spring AI ToolCallingAdvisor against a mocked provider, including function callback execution, tool context, ordered encrypted reasoning/history replay, absent usage, and incomplete responses. This is offline integration evidence, not provider compatibility evidence.

Full Checkstyle main/test passed; changed Java was formatted with Spotless. Logs: `responses-verify-v2.log` (59 passed; three adapter failures subsequently corrected), `responses-adapter-final.log` (native tool replay and incomplete responses passed; absent-usage assertion subsequently corrected), `responses-empty-usage-final.log` (last assertion passed), and `responses-style.log` (style passed). Spring AI exposes missing usage as EmptyUsage; the passive HTTP ledger continues to record it as unknown cost.

No additional provider requests were made for the adapter change. The original smoke's measured EUR 0.0092546344 and unresolved EUR 0.1345096121 reservation remain preserved. A new manifest or evidence directory cannot reset the EUR 1 allowance. Formal results remain pending.

## Completed live Responses smoke and corrections

The final smoke passed both scheduled observations and all four coverage gates; all 30 provider attempts reconcile with Logos. See [SMOKE.md](SMOKE.md) for costs, timing, persisted changes, and the original allowance audit. No formal observations ran.

Live startup exposed ambiguous constructor binding in the benchmark properties record; the canonical constructor is now explicitly bound. Live Responses exposed the SDK model union variant; all three model variants now preserve exact identity. Luna cache-write pricing is now captured, reconciled, and included in conservative dispatch reservations. Both old failed smoke configurations remain immutable and unmeasured where evidence is missing.

`responses-cache-write-check.log`: 25 tests passed (17 accounting/budget, 6 adapter, 2 binding), production/test compilation and full main/test Checkstyle passed. Python runner/report tests: 11 passed. Changed Java formatted with Spotless. `live-model-identity-check.log` in the separate PR worktree also records six passing adapter tests. No commits or pushes were created.

## Formal EUR 20 preparation

The runner default and pre-dispatch formal ceiling were raised together to EUR 20 at Justin's request. The original smoke ceiling remains EUR 1. The new parameterized guard test verifies both mode ceilings with outstanding reservations, even if an active file requests a larger budget. `formal-budget-check.log` records 19 passing accounting/guard tests, compilation, changed-file Spotless formatting, and full main/test Checkstyle. All 11 Python tests and shell syntax checks passed. Formal execution remains for Justin to initiate, using FORMAL-RUN.md.

Live preparation completed for `atlas-logos-formal-20260910-final-20eur`: 60 fresh courses, 3,600 exercises, and 80 frozen observations. Read-only API verification matched every course's initial persisted state, confirmed 60 exercises per course, and confirmed automation disabled on all 60 new and ten earlier smoke courses. Logos export remained exactly the same 34 request IDs before and after preparation, proving zero new provider requests. No formal observations or dispatch reservations exist. The detailed `preparation-audit.json` and empty `pre-run-report.json` live in that campaign's evidence directory. Artemis and the isolated Logos stack remain running for user dispatch.

## Recovery and all-Luna revision

The recovery ledger lane passed 21 tests, including captured unknown usage across observations and restart, uncaptured dispatch blocking, concurrent reservations, and mode budget exhaustion. Compilation, changed Java formatting, and full main/test Checkstyle passed in `recovery-v2-check.log`. All 15 Python tests pass, covering independent observations after incomplete usage, progress/retry display, interruption during cleanup, immutable evidence, lowered frozen budgets, and exact terminal invocation identity. Ruff and shell syntax checks pass.

The final review identified terminal invocation matching and cleanup interruption gaps, which were corrected and tested. Its proposed manual-course-ID blocker was not present: the aspect only treats the first argument as a course ID for multi-argument entry points; one-argument manual methods pass no course ID to that check. Existing and subsequent live manual smoke evidence exercises this route.

The abandoned formal timeout had `sdkRetryCount=0`; the next request had `sdkRetryCount=1` and succeeded. The SDK already retried it. The revised benchmark profile explicitly sets five minutes and three retries, while captured unknown usage retains its reservation without cancelling independent observations. All new benchmark model phases use Luna, with xhigh/high/medium reasoning for orchestrator/worker/flavor stripping respectively. Historical Mini evidence remains unchanged.

## Shared tool budget correction — 11 September 2026

The revised autonomous path uses one atomic budget of 128 attempted callbacks for the main orchestrator and all nested workers. Every exposed callback is decorated; reads, writes, delegations, completion, and failed calls consume slots. The native Spring AI advisor continues to own the loop, with its separate per-tool/total quotas disabled only for autonomous calls. Exhaustion preserves provider usage and applied actions, returns `TOOL_CALL_LIMIT_EXCEEDED`, and prevents scheduler replay.

Offline verification passed: 98 tests across the budget, delegation, orchestration, scheduler, Responses adapter, benchmark accounting, and Atlas code-style/service architecture suites. After replacing forbidden null precondition calls with JSpecify annotations, all 10 budget tests passed again and full `checkstyleMain` / `checkstyleTest` reported zero violations. Changed Java files were formatted with Spotless. Logs and XML are preserved in `build/atlas-tool-budget-verification/`.

The native-loop tests establish: 48 calls to one tool succeed, 128 callbacks can complete, callback 129 is blocked, parallel callbacks cannot overspend, nested workers share the parent counter, parent execution stops without another provider round after worker exhaustion, and accumulated usage remains available. Service/scheduler tests verify retained partial actions and no automatic replay.

No paid provider call or campaign restart was performed for this correction. Existing campaign evidence and its frozen runtime were preserved; this revision requires separate smoke validation and a new frozen campaign configuration before collecting comparable new observations.

The develop-based PR worktree also passed fresh production/test compilation, 93 behavior tests, six architecture checks, Spotless, and full Checkstyle. Its verification is preserved in `../atlas-responses-luna-pr/build/atlas-responses-pr/tool-budget-verification.md`. A task-local Gradle output collision was corrected: the cache init now redirects only this benchmark checkout, and mixed generated outputs were preserved at `/Volumes/External SSD/Codex/atlas-logos-benchmark-20260910/build-before-tool-budget-isolation`. The next benchmark build regenerates its configured output directory from scratch. The campaign's internal evidence and staged runtime remain in place.
