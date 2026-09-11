# Formal campaign handoff

Campaign `atlas-logos-formal-20260911-tool-budget-v1` was launched on 11 September 2026 after fresh smoke validation. It prepares 60 fresh courses, then executes 60 primary observations and 20 bootstrap continuations sequentially. The shared application limit is 128 attempted tool callbacks per invocation across the orchestrator and nested workers. All phases use Luna. AtlasML remains disabled.

The formal dispatch allowance is EUR 18.2867564023 after carrying forward earlier measured charges and unresolved holds within the authorized EUR 20 formal allowance. The original EUR 1 smoke allowance remains separate. Fresh smoke cost EUR 0.0142345778 and passed both manual and automatic paths with 32 reconciled provider attempts, nested workers, persisted changes, and no missing usage or integrity issues. See SMOKE.md. A spending ceiling does not guarantee all observations will finish. The supervisor criterion remains EUR 0.50 per invocation.

## Current local run

The server and runner are durable macOS launchd jobs, `dev.lanfermann.atlas-benchmark-server` and `dev.lanfermann.atlas-benchmark-runner`, using definitions in ignored `build/atlas-logos-benchmark/background-tool-budget/`. They prevent idle sleep and do not automatically restart failed processes. The runner prepares fixtures before formal dispatch. A ten-minute Codex heartbeat checks progress and pauses after completion or a hard blocker. Do not start a second runner while these jobs are active.

```sh
cd /Users/justin/Programming/University/artemis2/.worktrees/thesis-logos-benchmark-20260910
source build/atlas-logos-benchmark/formal-tool-budget.env
tail -f build/atlas-logos-benchmark/formal-tool-budget-run.log
```

The runner writes its exit status to `build/atlas-logos-benchmark/formal-tool-budget-run.exit`. Evidence is under `build/atlas-logos-benchmark/evidence/atlas-logos-formal-20260911-tool-budget-v1/`. The frozen manifest, schedule, runtime hashes, allowance carry-forward, and preflight smoke report record this configuration. Credentials and local runtime files remain ignored.

Do not change source, fixtures, pricing, or schedule after freeze. Do not delete evidence, reservations, or stop markers to replay failed observations. Earlier campaigns remain separate historical evidence. Native provider retries are allowed; whole observations are never automatically replayed after possible persisted changes. Missing usage stays unknown with its reservation held.

After completion or interruption, reconcile and report:

```sh
scripts/run-atlas-logos-benchmark.sh stack export "$ATLAS_BENCHMARK_EVIDENCE_DIR/logos.jsonl"
scripts/run-atlas-logos-benchmark.sh report --mode formal > "$ATLAS_BENCHMARK_EVIDENCE_DIR/report.json"
```

Report all planned observations, separating primary observations, continuations, and failures. Inspect integrity issues before transferring numbers to the thesis. Results characterize this local configuration; they do not establish production throughput or general affordability.

To request a graceful stop before further provider dispatch:

```sh
scripts/run-atlas-logos-benchmark.sh stop --mode formal
```

Allow current invocation cleanup to disable course automation. Once the runner has terminated, unload its job and the server:

```sh
launchctl bootout gui/$(id -u)/dev.lanfermann.atlas-benchmark-runner
launchctl bootout gui/$(id -u)/dev.lanfermann.atlas-benchmark-server
```

The heartbeat performs reconciliation and job cleanup after completion. Docker volumes and evidence stay intact. Inspect active automation before any recovery from forced process loss; never blindly restart the runner.

## Another installation

Use the single shell entry point with your own isolated Artemis database and Logos URL/key, as documented in README.md. Prepare a new campaign with its own frozen manifest and recorded Logos revision, then run the reviewed smoke checks. Formal dispatch requires `ATLAS_BENCHMARK_FORMAL_CONFIRMATION=RUN-ATLAS-LOGOS-FORMAL scripts/run-atlas-logos-benchmark.sh run`. Do not copy this machine's credentials. Preserve sanitized evidence with the submission.

## Historical recovery notes

The following notes describe earlier campaigns, not the current launch.

## Authorized startup reset on 10 September 2026

The first launch after the server restart did not enter Atlas: the in-memory AtlasAgent toggle had reset to disabled. The interrupted observation and original manifest/logs were archived separately. After explicit operator authorization, the first course was restored to its verified initial state and the schedule was reset before any provider dispatch. Both Artemis journals and an unchanged Logos export confirmed no requests from this attempt. The allowance was unchanged. The revised runner enables and verifies AtlasAgent, with AtlasML disabled, before recording an observation as started. Its revised source hash is frozen before relaunch; this infrastructure correction does not change model prompts, tools, fixtures, or schedule.

## Local process recovery on 10 September 2026

A ChatGPT desktop crash at 23:06:39 CEST terminated the attached server and runner during repetition 8 bootstrap. The observation remains interrupted; its dependent continuations are not replayed. Two durable reservations lacked response capture. Explicit recovery records label their phase and usage unknown and retain both conservative holds; original records are archived under `process-loss-recovery/`. No usage is reconstructed from timing alone.

The local runtime now uses macOS launchd jobs `dev.lanfermann.atlas-benchmark-server` and `dev.lanfermann.atlas-benchmark-runner`, with definitions in ignored `build/atlas-logos-benchmark/background/`. They run the same compiled classes and configuration, staged internally because launchd cannot access this machine's external SSD cache. Neither job automatically restarts failed runs. `caffeinate` prevents idle sleep while these jobs run. The standard shell entry point remains usable on other installations. Unload the local jobs after completion with `launchctl bootout gui/$(id -u)/dev.lanfermann.atlas-benchmark-runner` and the equivalent server label.
