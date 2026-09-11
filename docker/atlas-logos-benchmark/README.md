# Atlas through local Logos

Normal Artemis events and instructor actions use the Spring AI tool loop, Logos, and OpenAI. The scripts only prepare synthetic courses, schedule observations, and report passive HTTP evidence. AtlasML is disabled. All benchmark code and configuration live in this Artemis worktree.

**Fresh smoke passed on 2026-09-11 with the shared 128-call budget.** Both trigger paths completed with workers and persisted changes. All 32 provider attempts reconciled, costing EUR 0.0142345778 combined. See [SMOKE.md](SMOKE.md). The new formal campaign has been launched; results remain pending.

For the authorized formal campaign on this machine, use [FORMAL-RUN.md](FORMAL-RUN.md). A durable runner and ten-minute heartbeat manage this launch; do not start a duplicate runner.

## Use an existing Logos instance

The single entry point is `scripts/run-atlas-logos-benchmark.sh`. Use Java 25 and Python 3.10+. Set `LOGOS_BASE_URL` to the OpenAI-compatible base URL of your Logos instance (including `/v1`) and `LOGOS_API_KEY` to a key with access to `gpt-5.6-luna`. Do not substitute models silently. All three phases use `gpt-5.6-luna`: orchestrator `xhigh`, workers `high`, and flavor stripping `medium` reasoning. The benchmark profile sets a five-minute request timeout and three native retries.

Set `LOGOS_REVISION` to the deployed Logos commit before preparation; an unspecified external revision is recorded as unknown. For the optional pinned local stack, use `0d4a6683171cd956e1cf46a19d49e99181a52ac4`. The manifest freezes the configured Logos URL.

No OpenAI key is needed by Artemis. Your Logos administrator configures its OpenAI upstream. Use an isolated Artemis database; standard `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` variables override the local defaults. Never point fixture preparation at a production Artemis instance.

Local Docker is optional. Skip the following stack section when using an existing Logos instance.

## Optional local stack

Run from this worktree with Docker Desktop running, Java 25, Python 3.10+, and Git available:

```sh
export LOGOS_REVISION=0d4a6683171cd956e1cf46a19d49e99181a52ac4
scripts/run-atlas-logos-benchmark.sh stack setup
scripts/run-atlas-logos-benchmark.sh stack up
export OPENAI_SECRET_FILE=/absolute/path/to/local.env
scripts/run-atlas-logos-benchmark.sh stack provision
scripts/run-atlas-logos-benchmark.sh stack models
```

The secret file contains `OPENAI_API_KEY=...` and is parsed as data. Provisioning checks OpenAI's model inventory without inference, then configures one upstream and one restricted Logos key. Artemis receives only the generated Logos key. Do not put either key in evidence or shell history.

The official source revision is `0d4a6683171cd956e1cf46a19d49e99181a52ac4`. Compose owns only project `atlas-thesis-logos-20260910`, its network and volumes. Localhost ports are Logos 18090, its administration service 18092, Keycloak 18095, Artemis PostgreSQL 5439, and Artemis 8083. `stop` retains all data and evidence.

## Preparation and execution

The lifecycle entry point is `scripts/run-atlas-logos-benchmark.sh` with `prepare`, `smoke`, `run`, `report`, and `stop`. Offline preparation makes no provider calls:

```sh
scripts/run-atlas-logos-benchmark.sh prepare --offline --mode formal
```

Use the same absolute `ATLAS_BENCHMARK_EVIDENCE_DIR` in the server and runner terminals. Start Artemis with `scripts/run-atlas-logos-benchmark.sh server`. Set isolated local administrator credentials through `ARTEMIS_USERNAME` and `ARTEMIS_PASSWORD` before live preparation. Live preparation creates fixture data through ordinary APIs with automation disabled.

The formal campaign's frozen schedule has 60 primary observations (six conditions, ten randomized repetitions) and 20 separate bootstrap continuations. Failed or interrupted observations are retained and never replaced. Missing usage is unknown cost. The local five-second debounce serves practicality; report event-to-completion delay separately from orchestration execution time.

## Measurement

The profile `atlas-logos-benchmark` adds a guard and passive interceptor to the existing Spring AI HTTP client. Every dispatch reserves conservative input and maximum-output cost before networking; unresolved attempts retain their reservation. Normal prompts, tools, model choices, and SDK retries remain part of the measured application path.

Reconcile captured `logosRequestId` values with Logos `requestId` values:

```sh
scripts/run-atlas-logos-benchmark.sh stack export "$ATLAS_BENCHMARK_EVIDENCE_DIR/logos.jsonl"
scripts/run-atlas-logos-benchmark.sh report --mode smoke
```

For an existing Logos instance, obtain the same metadata export from its administrator and save it as `logos.jsonl`. Each JSONL row has `requestId`, `model`, `status`, `startedAt`, `endedAt`, and `usage` with `prompt_tokens`, `prompt_cached_tokens`, `prompt_cache_write_tokens`, and `completion_tokens`. No raw request bodies or credentials are needed. Without matching Logos evidence the report must remain unreconciled.

`pricing.json` records dated official model prices and the dated ECB exchange rate. Cached reads and cache writes are disjoint subsets of input; Luna cache writes cost 1.25 times its uncached rate. Output already includes reasoning tokens. Unsupported prices and missing usage remain unmeasured. The supervisor's EUR 0.50 criterion applies per invocation; report complete bootstrap workflow totals separately. These measurements characterize this local configuration and do not establish production throughput or general affordability.

The source reconciliation snapshot and original-checkout preservation checks are retained under `build/atlas-logos-benchmark`. The original checkout, its index, and previous benchmark evidence are preserved. This worktree contains no new commits.

## Smoke handoff

Run these commands only after the isolated services are available. Use the same environment variables in both terminals; keep credentials out of committed files.

```sh
export ATLAS_BENCHMARK_EVIDENCE_DIR="$PWD/build/atlas-logos-benchmark/evidence/atlas-logos-smoke-20260910"
scripts/run-atlas-logos-benchmark.sh prepare --offline --mode smoke
# Terminal 1: foreground Artemis process
scripts/run-atlas-logos-benchmark.sh server
# Terminal 2: with ARTEMIS_USERNAME / ARTEMIS_PASSWORD set
scripts/run-atlas-logos-benchmark.sh prepare --mode smoke
scripts/run-atlas-logos-benchmark.sh smoke
# Local Logos only; otherwise obtain the equivalent metadata export.
scripts/run-atlas-logos-benchmark.sh stack export "$ATLAS_BENCHMARK_EVIDENCE_DIR/logos.jsonl"
scripts/run-atlas-logos-benchmark.sh report --mode smoke
```

The smoke schedule has a manual exercise revision and an automatic lecture-unit revision. A read-only Logos model inventory check runs before dispatch. Model/reasoning support and tool execution are checked from these normal Atlas invocations; no separate model probe or replacement loop runs. `smokeReady` requires reconciled usage, worker/model/reasoning coverage, a persisted mapping change, and automatic completion. If the allowance prevents coverage, the report stays incomplete. This combined check passed in the Responses smoke documented in SMOKE.md.

For formal preparation, use a separate evidence directory ending in `atlas-logos-formal-20260910`, then `prepare --offline --mode formal`. After live preparation and reviewed smoke evidence, formal dispatch additionally requires `ATLAS_BENCHMARK_FORMAL_CONFIRMATION=RUN-ATLAS-LOGOS-FORMAL`. Formal dispatch must be explicitly authorized. The formal cap is EUR 20, separately authorized from the original EUR 1 smoke allowance; prior charges and unresolved holds reduce the current campaign allowance. Preserve these evidence directories and the dispatch journals; creating another directory starts a different campaign, not a spending reset for this experiment.

The revised application cap is **128 attempted tool callbacks per invocation**, shared by the orchestrator and all nested workers. Reads, writes, delegations, failed callbacks, and completion calls count; provider HTTP requests do not. The 129th callback is blocked before execution. Limit exhaustion preserves partial changes and usage, returns `TOOL_CALL_LIMIT_EXCEEDED`, and does not automatically requeue the run. The provider dispatch budget separately limits smoke spending. Technical checks permit any autonomous competency count after execution; reference counts constrain fixture inputs only.

`stop --mode smoke` writes the stop signal read before further provider dispatch. The runner disables the active course when its current invocation terminates. Stop a foreground server with Ctrl-C; stop only the optional local containers with `stack stop`. Neither command deletes volumes or evidence.

See [VERIFICATION.md](VERIFICATION.md) for checks and [SMOKE.md](SMOKE.md) for the latest runtime evidence, and [formal-results.csv](formal-results.csv) for the 80-row empty result template.

## Atlas-only Responses transport

`artemis.atlas.orchestrator.responses-api-enabled` opts autonomous Atlas orchestration and nested workers into Responses. It defaults to false and is enabled in the benchmark profile. Interactive Atlas chat, Hyperion, and flavor stripping retain their existing clients. Logos must allow `/v1/responses` for the requested models.

The adapter implements Spring AI `ChatModel`; Spring AI's `ToolCallingAdvisor` executes the existing callbacks and advances the conversation. Every request carries the complete history with `store=false`, including encrypted reasoning items and matching function outputs. Spring AI’s per-tool and per-loop quotas are disabled only for autonomous rounds; one atomic callback budget enforces the shared 128-call limit. Interactive Atlas retains its existing client behavior.

The benchmark interceptor captures Responses usage and nested reasoning effort. It rejects stored conversation references and unmeasured reasoning history; replayed encrypted reasoning also reserves its previous measured output-token bound, since ciphertext size alone cannot establish token cost. Existing incomplete reservations and failed smoke evidence remain intact. Captured unknown-usage attempts retain budget holds while later independent observations may continue under the revised recovery policy.

See [PR-FOLLOWUP.md](PR-FOLLOWUP.md) for the planned upstream PR. For a new campaign, carry forward all previous unresolved reservations, prepare a new manifest against this transport, and keep all smoke attempts within the original EUR 1 allowance. Do not rerun the failed observations or use an old manifest with changed code.

## Recovery revision

See PROTOCOL.md for the replacement-campaign policy. Recoverable HTTP failures use native SDK retries. Missing usage retains its reservation and remains unknown, but no longer cancels independent observations. Hard budget/integrity/isolation failures still stop dispatch. The CLI now prints ten-second progress and a terminal summary. Operator stops leave remaining observations pending. No whole orchestration is replayed automatically after it may have changed course state.

The shared callback limit was added after campaign `atlas-logos-formal-20260910-recovery-v2`. That campaign retains its original runtime and evidence, including failures under Spring AI’s default tool limits. The new `atlas-logos-formal-20260911-tool-budget-v1` campaign has a separate frozen manifest and passed fresh smoke validation.

### Graceful tool-budget configuration

Autonomous runs share 256 attempted callbacks. Every tool response carries the remaining budget; after 224 callbacks, only reads and completion may execute. Partial changes are retained, and unresolved work remains a partial/failure outcome. There is no extra unbudgeted model call on hard exhaustion. Extracted learning content is reused within the invocation; mapping reads remain fresh.

For a complete preflight rehearsal, export `ATLAS_BENCHMARK_REHEARSAL=1` before `prepare --mode smoke` and `smoke`. This schedules one repetition of all six conditions and both bootstrap continuations. The report requires all eight observations to be valid-completed for `smokeReady`. Use a new campaign identifier after a code/configuration change, carry forward spending and unknown reservations, and preserve prior evidence. Formal results must not combine different frozen configurations.

The runner verifies automation settings through the regular instructor course endpoint, which loads course configuration. The course-with-content view may leave that association unloaded and is unsuitable for confirming the automation kill switch. GET transport failures retry; uncertain setting updates are checked by read-back. Orchestration triggers are never replayed.
