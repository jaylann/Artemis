# Atlas cost benchmark

This workspace started as a copy of `atlas-demo` and now integrates current develop and the four refreshed Atlas PRs, with an external cost meter and the heterogeneous thesis fixture. Local application changes are confined to the demo’s Hazelcast isolation and one combined-PR test adaptation. The meter forwards the original Responses request and response bodies; it adds no application hooks or behavioral fixes. See [SOURCE-STATE.md](SOURCE-STATE.md) for the exact develop/PR/local composition.

**State: refreshed PR integration; preparing the verified source for Justin’s commit.** No source commit, campaign freeze, publication, paid smoke/formal execution, or thesis result replacement has occurred. Recheck heads before the final handoff. Any subsequent source update requires affected verification before freezing either paid campaign.

## Files

- `meter.py`: localhost forwarding, durable per-attempt reservations, observed usage and decimal EUR accounting. SDK retries remain distinct attempts. It forwards directly to OpenAI by default, matching the demo; `--upstream` can select a local Logos `/v1` endpoint. A Logos deployment and reconciliation are not part of this minimal setup. Evidence must name the route actually used.
- `campaign.py`: immutable campaign preparation, sequential dispatch, complete scheduled-observation reporting, and source/runtime/price checks.
- `seed.py`, `heterogeneous.py`, `fixture-heterogeneous.json`: ordinary Artemis API calls and the existing synthetic fixture. No benchmark REST endpoints are added to Artemis.
- `observe.py`: the existing instructor STOMP completion notification for automatic runs, and the scheduler’s existing terminal DEBUG log for `NO_OP` results that do not broadcast a notification. Manual runs use the normal HTTP result. No timing-based inference of completion.
- `server.sh`: copied-demo launcher with separate ports, Compose project, Hazelcast cluster, and the meter endpoint. Mutable state remains under this copy’s ignored `.demo/` directory.

The seed is `20260910`. Formal design: six conditions × ten repetitions, 60 isolated courses and 90 invocations including bootstrap continuations of 12/24/24/12 objects. The three-observation smoke has its own courses and ID. The cost acceptance criterion is **EUR 15 per invocation**. Campaign spending ceilings are independently **EUR 1 smoke / EUR 20 formal**, counting settled attempts and outstanding worst-case reservations. Atlas uses Luna through Responses: xhigh orchestration, high workers, high flavor stripping. Its native 256-callback limit and wrap-up at 224 are unchanged; AtlasML stays disabled.

## Operation after source verification and Justin’s commit

Source must be committed by Justin, pushed to `jaylann/Artemis` branch `feat/atlas-benchmark-cap256-20260911`, and match the remote SHA before `run --paid` will dispatch. Prepare manifests only after that commit; use distinct, never-reused IDs. Run commands from this copied checkout.

```bash
python3 -m venv .demo/venv
.demo/venv/bin/pip install -r supporting_scripts/benchmark/requirements.txt
supporting_scripts/benchmark/server.sh build
# Supply OPENAI_API_KEY via the environment or this copy's private .demo/openai.env.
supporting_scripts/benchmark/server.sh start

.demo/venv/bin/python supporting_scripts/benchmark/campaign.py prepare --mode smoke --campaign .demo/benchmarks/CHOOSE-SMOKE-ID
.demo/venv/bin/python supporting_scripts/benchmark/campaign.py seed --campaign .demo/benchmarks/CHOOSE-SMOKE-ID
.demo/venv/bin/python supporting_scripts/benchmark/campaign.py run --campaign .demo/benchmarks/CHOOSE-SMOKE-ID --paid

.demo/venv/bin/python supporting_scripts/benchmark/campaign.py prepare --mode formal --campaign .demo/benchmarks/CHOOSE-FORMAL-ID
.demo/venv/bin/python supporting_scripts/benchmark/campaign.py seed --campaign .demo/benchmarks/CHOOSE-FORMAL-ID
.demo/venv/bin/python supporting_scripts/benchmark/campaign.py run --campaign .demo/benchmarks/CHOOSE-FORMAL-ID --smoke .demo/benchmarks/CHOOSE-SMOKE-ID --paid
supporting_scripts/benchmark/server.sh stop
```

Ports: Artemis 8083, PostgreSQL 55433, Git SSH 7924, Hazelcast 5704, request meter 18091. The original demo uses different resources. No runtime files, credentials, databases, or repositories were copied from it. The server’s API key is forwarded to the configured upstream; the meter never writes it to the ledger. The meter is available only while the campaign command is running. Before execution, verify Docker health, free disk/storage, port availability, model access, the runtime build, and current pricing. Live preflight and smoke remain pending.

Automatic observations subscribe before triggering and verify the native batch count. The existing scheduler DEBUG logger is enabled through `SPRING_APPLICATION_JSON` to preserve its case-sensitive class name; matched firing/terminal log records establish `NO_OP` completion. A native per-course daily cap equal to the bootstrap stage prevents a failed batch from being replayed by a later scheduler tick; courses are disabled again after each observation. This is an explicit experimental course setting, not a scheduler modification. Mutating HTTP calls are never automatically retried. A dispatch marker is written before changing content; interrupted seeding or execution requires inspection and cannot be replayed with the same command. Failed dependencies remain skipped in the report.

## Evidence and limits

The private campaign directory holds the manifest, course identifiers, API write journal, completion outcomes, before/after state, provider attempts, and report. All scheduled rows remain visible: completed, failed/partial, invalid/unmeasured, and skipped. Automatic notifications group failed and partial outcomes together; the report preserves that limitation. Model-issued function names are recorded, but this meter does not claim to count actual Java callback executions. Phase labels are inferred from the frozen request shapes (reasoning effort and presence of function tools), so execution must stay isolated from other users and LLM features.

Successful, supported usage replaces each attempt’s conservative maximum reservation. Missing usage, upstream errors and timeouts retain their reservation, even if the SDK later succeeds. Incomplete cost evidence cannot pass; any measured lower bound over EUR 15 fails. Output tokens include reasoning; reasoning is not billed a second time. Cache reads/writes and the long-context multipliers use the frozen `pricing.json` and dated ECB conversion. The reporter independently recomputes settled costs. Source, pricing, fixture, or built-WAR mismatches block execution; configuration mismatches block forwarding. This extra HTTP hop adds measurement overhead, so latency is not identical to the unmetered demo.

The historical campaign directories remain unchanged in `benchmark-results/` and their original commits. Their original analyzer remains available through those immutable revisions; the new report format does not reinterpret archived campaigns. Publishable evidence, hashes, updated analysis/figures and canonical thesis prose will be produced only after live verification. No historical comparison or rerun narrative belongs in the thesis.

## Offline checks

```bash
python3 -m unittest discover -s supporting_scripts/benchmark -p 'test_*.py'
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew -x webapp -x autoLintGradle \
  -I supporting_scripts/benchmark/runtime-classpath.init.gradle \
  -PbenchmarkClasspathOutput="$PWD/.demo/validation/classpath.txt" writeAtlasBenchmarkClasspath
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  python3 supporting_scripts/benchmark/verify_fixture.py --classpath-file .demo/validation/classpath.txt --output .demo/validation
```

Paid execution is intentionally not a test target. The local transport test uses a fake upstream and verifies exact request/response bytes. The complete application copy, integration changes, and harness are reviewable before Justin creates the source commit.

Verification records for the refreshed source are under `.demo/refresh-20260922/`; earlier copy checks remain separately under `.demo/validation/`. The diff against the older fork flags existing whitespace in develop’s generated OpenAPI files; those files are intentionally preserved. These offline checks are not a live-campaign result.

Refreshed-source verification on 22 September: the integrated server run executed 781 tests; two log-capture assertions failed during simultaneous Spring context startup. Both passed unchanged in the separate run, which also checked the shared and Atlas architecture rules (102 tests, zero failures, one intentional skip). Client verification passed 262 tests in 11 suites and TypeScript compilation. All 11 harness tests and all 144 fixture DTO/extraction cases passed. Checkstyle, Modernizer, the scoped Java formatting check, server dead-code scan and scoped ESLint passed; ESLint reported two existing deprecation warnings. GitHub CI for the exact PR heads was still queued/running at handoff and is not claimed green.
