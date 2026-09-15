# Atlas benchmark evidence and reproduction

The heterogeneous campaign is the primary thesis evaluation. The earlier text-only
campaign is retained for a descriptive comparison. Both are synthetic local
provider-cost characterizations, not mapping-quality or production studies.

| Campaign | Observations | Requests | Provider EUR | Maximum invocation EUR |
| --- | ---: | ---: | ---: | ---: |
| `atlas-logos-heterogeneous-formal-fresh-20260914-v1` | 90 | 2,639 | 1.744587 | 0.045344 |
| `atlas-logos-formal-20260911-cap256-v1` | 80 | 2,884 | 0.978442 | 0.024445 |

## Reproduce the published calculations without provider calls

Requirements: Python 3.10+ for accounting without figures, or Python 3.12+ with
the pinned figure dependencies. The writing batch used CPython 3.14 and Matplotlib
3.11.2. The requirements file pins its transitive runtime dependencies too.
From the repository root:

```sh
python3 -m venv /tmp/atlas-analysis-venv
/tmp/atlas-analysis-venv/bin/pip install -r tools/atlas-benchmark-analysis/requirements.txt
MPLCONFIGDIR=/tmp/atlas-matplotlib /tmp/atlas-analysis-venv/bin/python \
  tools/atlas-benchmark-analysis/analyze.py \
  --evidence benchmark-results/atlas-logos-heterogeneous-formal-fresh-20260914-v1 \
  --baseline benchmark-results/atlas-logos-formal-20260911-cap256-v1 \
  --output /tmp/atlas-analysis
python3 -m unittest discover -s tools/atlas-benchmark-analysis -p 'test_*.py'
```

Dependency installation needs network access. Once installed, the analysis is
fully offline. Add `--no-figures` to use only the Python standard library.
The output directory must be separate from either evidence package. Outputs are
`audit.json`, `baseline-audit.json`, `summary.json`, `comparison.json`, observation
CSV/JSON, `tables.typ`, `observations-appendix.typ`, and two SVG figures.
The analyzer never imports the live runner and never substitutes unknown usage
with zero. It refuses corrupted or incomplete versions of these complete datasets.
It is not a general-purpose classifier for newly executed partial campaigns.

## What is preserved

Each campaign has byte-preserved input, schedule, pricing, observation, provider,
invocation, dispatch and report files, plus a frozen source snapshot. Every file
is covered by `checksums.json`. The source map reproduces the original manifest's
composite fingerprint. The old snapshot comes from the post-execution source
archive commit `68f8023db3126c3ea9c9e8b3bf75231118525e15`. The new snapshot contains
the 248 source entries and the separately selected campaign pricing file, for
249 entries in its composite fingerprint.

`archive-provenance.json` identifies original file hashes and transformations.
Only `logos.jsonl` is filtered: complete original lines are retained exactly when
their request IDs belong to the campaign. This removes unrelated historical and
smoke traffic. The provider/invocation/dispatch/observation journals are unchanged.
Original execution-time manifests, commit IDs, paths and prices are never rewritten.
The archive was prepared after execution and must not be described as the execution
commit itself. The publication commit is the commit introducing this package. It is separate from
the execution-time revision and does not replace the original manifest provenance.

Runtime hashes identify the measured binary snapshot. They are retained as evidence,
not a promise that another compiler, platform, or dependency resolver reproduces
byte-identical binaries. Offline accounting needs neither runtime binaries nor a
running Artemis/Logos service. Local raw checkpoints, private database dumps and
storage backups remain outside this repository package. They are not required to
reproduce the calculations. No credentials or environment secrets are packaged.

## Repeat the experimental protocol

A new provider run is a new experiment. It requires model access, explicit spending
authorization, a new campaign ID, fresh isolated courses/databases, new evidence,
and a fresh manifest. It cannot reproduce the exact stochastic decisions or costs.
Never replay an already attempted observation or append a new run to this archive.

Use a clean checkout of this branch. Restore the chosen `source/` snapshot over that
checkout if source has subsequently changed. It contains the measured Atlas code,
prompts, benchmark Java/Python code, fixture and configuration. The rest of Artemis,
Gradle wrapper and pinned dependency versions come from the archive branch's base.
The new source snapshot also restores its execution-time pricing-file path.
Do not copy the snapshot over an unrelated dirty checkout.

Prerequisites are Java 25, the repository Gradle wrapper, Python 3.10+, Git, and
Docker for the isolated local stack. The standard server entrypoint is
`scripts/run-atlas-logos-benchmark.sh server`, which builds via `bootRun -x webapp`
with `dev,artemis,localci,localvc,core,scheduling,atlas-logos-benchmark` profiles.
Use the repository's existing Logos setup instructions in
`docker/atlas-logos-benchmark/README.md` for installation, while the following frozen
configuration and `scripts/atlas-logos-benchmark/HETEROGENEOUS.md` govern this study:

- Official Logos revision `0d4a6683171cd956e1cf46a19d49e99181a52ac4`, OpenAI upstream.
- `gpt-5.6-luna`: orchestrator `xhigh`, workers `high`, flavor stripping `medium`.
- AtlasML disabled. Shared budget 256 callbacks, wrap-up 224, completion reserve.
- Five-second automatic debounce, one-second scheduler interval, sequential seeded order.
- Heterogeneous fixture: 60 exercises and 12 lecture units; bootstrap 12/24/24/12 objects.
- Dated prices in the selected campaign. New runs must verify and freeze their own
  prices/model support, without changing the historical archive.

Set `LOGOS_BASE_URL`, `LOGOS_REVISION`, `LOGOS_API_KEY`, isolated
`SPRING_DATASOURCE_*`, `ARTEMIS_USERNAME`, and `ARTEMIS_PASSWORD` privately.
Give the server and runner identical evidence, fixture and pricing settings.
Use unique Compose project names, loopback ports and storage paths. The stack
wrapper selects the repository Compose file explicitly, so `COMPOSE_FILE` does not
override it. That file's published ports are fixed. To run alongside another stack,
make a private copy of the Compose configuration, change every conflicting published
port and its matching issuer/CORS URL, and resolve its relative build and volume
paths against the original directory. Use the same private environment file and
explicit `docker compose --project-name NEW_NAME --env-file PRIVATE_ENV --file
PRIVATE_COMPOSE` prefix for configuration, startup, status and shutdown. A unique
project name alone does not isolate published ports. Keep credentials out of Git.

For Artemis, set matching `SERVER_PORT`, `SERVER_URL`, `SPRING_DATASOURCE_URL`,
database credentials, `SPRING_HAZELCAST_PORT`, local version-control URLs and storage
paths in the isolated checkout's private configuration. Validate Compose with
`config --quiet` before starting it. The original
run used Artemis 8084, PostgreSQL 5440, Logos 18091 and Hazelcast 5794; those port
numbers are isolation choices, not scientific parameters.

```sh
export ATLAS_BENCHMARK_FIXTURE=heterogeneous
export ATLAS_BENCHMARK_CAMPAIGN_ID=YOUR_NEW_CAMPAIGN_ID
export ATLAS_BENCHMARK_EVIDENCE_DIR=/absolute/path/to/new-evidence
export ATLAS_BENCHMARK_PRICING_FILE=/absolute/path/to/new-verified-pricing.json
scripts/run-atlas-logos-benchmark.sh prepare --offline --mode formal
```

Offline preparation must produce 90 positions and the 12/24/24/12 bootstrap stages.
The following build and extraction commands start no services and make no model
requests. Set `JAVA_HOME` to a Java 25 installation, then run from the repository
root. Gradle dependencies need network access on first installation. The wrapper and
repository dependency declarations pin the build inputs; the retained runtime hash
inventory identifies the particular measured binaries.

```sh
./gradlew --console=plain -x webapp \
  -I tools/atlas-benchmark-analysis/runtime-classpath.init.gradle \
  -PbenchmarkClasspathOutput=/absolute/path/to/new-check/classpath.txt \
  writeAtlasBenchmarkClasspath
python3 scripts/atlas-logos-benchmark/verify_heterogeneous.py \
  --classpath-file /absolute/path/to/new-check/classpath.txt \
  --output /absolute/path/to/new-check/fixture
python3 -m unittest discover -s scripts/atlas-logos-benchmark -p 'test_*.py'
./gradlew --console=plain -x webapp test --tests '*AtlasLogosBenchmarkTest'
```

The classpath helper is deliberately outside the frozen source directories. The
compiled check validates 144 DTO/extraction cases without database access. Do not
reuse an absolute classpath file from another machine or the measured runtime
snapshot. Validate the fixture with these checks before live preparation. Start the isolated services, verify
network/model access without inference, then run a separate three-observation smoke
campaign. Proceed to live formal preparation and dispatch only after smoke usage,
trajectory and cleanup checks pass, and after spending is explicitly authorized.
The runner requires an explicit budget, `ATLAS_BENCHMARK_PAID_CONFIRMATION`, and
`ATLAS_BENCHMARK_FORMAL_CONFIRMATION`; never set these for offline reproduction.
Use the existing `prepare`, `smoke`, `run`, and `report` commands with the new IDs.
Export matching Logos usage for reconciliation. Keep every failure and missing-usage
record, including all native SDK attempts. Preserve private operational backups.

The original heterogeneous run paused for 5,841.1566 seconds between completed
observations `r08-bootstrap-s1` and `r08-bootstrap-s2`. Its pause record is included.
Do not force an identical pause in a new run or claim the original was uninterrupted.

## Interpretation

Apply EUR 0.50 to individual invocations, not four-stage workflow totals. Changes in
fixture text, object mix/count, target revisions, bootstrap stages, cache state and
exchange rate prevent causal attribution of the cross-campaign difference to
heterogeneity alone. Earlier failed campaigns and their unknown-cost reservations
remain separate; this successful campaign does not resolve their missing usage.
