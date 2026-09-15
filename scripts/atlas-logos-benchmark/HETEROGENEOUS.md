# Heterogeneous coursework campaign

Select the new fixture with `ATLAS_BENCHMARK_FIXTURE=heterogeneous`. The default
remains the archived text-only fixture. `fixture.json` and the original campaign
evidence must remain unchanged. The new fixture is original agent-authored
synthetic coursework, reviewed for content and implementation compatibility;
it is not sampled production coursework or an expert-labelled mapping dataset.

## Course and observations

Twelve introductory computer-science topics each contain one programming, text,
modeling, file-upload, and quiz exercise, plus one lecture unit. There are 60
exercises and twelve units (four text, four online, four attachment/video),
organized into twelve topic lectures. Quiz questions cover multiple choice,
short answer, and drag and drop. Programming tasks describe Java coursework;
repository setup uses the ordinary API and stock Java templates. Empty-repository
setup requires Hyperion, which stays disabled. The extractor uses only authored
problem statements; stock repository contents are not coursework solutions, and
this campaign does not evaluate programming submissions or CI.

The four text units contain teaching prose. Online and video URLs under
`example.org` are explicitly synthetic placeholders, never fetched by the
extractor. Their instructor descriptions supply the learning text. This does
not test PDF parsing, URL fetching, video transcription, or media understanding.

One anchor per topic collectively covers all eight supported object types.
Initial links attach an object to its topic's reference competency:

| Condition | Initial competencies | Initial linked objects | Changed objects |
| --- | ---: | ---: | --- |
| Maintained | 12 | 72 | 12 anchors, meaning-preserving formatting edits |
| Assignment | 12 | 60 | 12 unlinked anchors, meaning-preserving formatting edits |
| Mixed | 6 | 30 | 12 unlinked anchors, meaning-preserving formatting edits |
| Bootstrap | 0 | 0 | Anchors first, then all remaining objects |
| Single exercise | 12 | 72 | One exercise, authored semantic revision |
| Lecture maintenance | 12 | 72 | One unit, authored semantic revision |

Each formal repetition runs all six primary conditions in seeded randomized
order, sequentially, on isolated courses. Bootstrap uses four dependent stages
of 12, 24, 24, and 12 objects. Ten repetitions yield 60 primary invocations and
30 continuations: **90 observations across 60 courses**. Single-exercise
maintenance rotates through all five types twice. Lecture maintenance covers
text four times and online and attachment/video three times each. These are
coverage counts, not ten observations per type.

Formatting edits append whitespace to content except for quizzes, whose title
terminal period is toggled. The archived quiz snapshot omits question stems,
while title setters normalize whitespace. The punctuation edit reaches the normal
versioning/event path without changing question text or answer keys. This is a
recorded harness adaptation, not proof that stem-only quiz edits trigger Atlas.
Attachment/video description updates explicitly specify `NO_FILE_CHANGE`.

The new smoke schedule has three observations: mixed-condition anchors (all eight
types), one manual exercise revision, and one automatic lecture revision.
It is separate from the formal dataset. Full rehearsal, when explicitly chosen,
contains nine observations. Never replace a failed observation with a retry.
Ordinary provider/SDK attempts remain accounted for within an invocation.

## Content and compatibility checks

`fixture-heterogeneous.json` holds the reviewed definition and semantic revisions.
Stable fixture keys are distinct from database IDs. Only allowlisted fields are
sent to Artemis; author notes and revision specifications stay outside initial
model-visible content. Programming example solutions remain authoring evidence,
as the programming extractor only uses the problem statement.

Run the focused offline tests:

```sh
python3 -m unittest discover -s scripts/atlas-logos-benchmark -p 'test_*.py'
```

Verify initial and revised payloads against a compiled Artemis runtime:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
python3 scripts/atlas-logos-benchmark/verify_heterogeneous.py \
  --classpath-file build/atlas-logos-benchmark/background-cap256/classpath.txt \
  --output build/atlas-logos-benchmark/heterogeneous-validation
```

This validates 144 DTO/extraction cases and records compiled-class hashes. It
does not open a database, execute the event/scheduler path, or invoke flavor
stripping or a provider. It cannot substitute for live CRUD and smoke evidence.

Live fixture preparation reads back every object's content and quiz answers,
checks ownership, and verifies initial link counts. Quizzes and attachment/video
units use their named multipart parts; the other types use their ordinary JSON
endpoints. Quiz readback mappings are translated into editor ID references for
meaning-preserving updates. Automatic triggers accumulate under a long local
debounce before releasing the complete batch.

## Freeze and paid execution

Use new, distinct smoke and formal campaign IDs and evidence directories. Offline
preparation makes no API calls:

```sh
export ATLAS_BENCHMARK_FIXTURE=heterogeneous
export ATLAS_BENCHMARK_CAMPAIGN_ID=atlas-logos-heterogeneous-formal-20260913-v1
scripts/run-atlas-logos-benchmark.sh prepare --offline --mode formal
```

The baseline's dated rates are retained for preparation only; verify the pricing
manifest and model access before freezing a measured campaign. Keep the archived
256-callback runtime and model/reasoning settings unless a necessary change is
documented and fingerprinted. A changed fixture, source, schedule, or pricing
manifest invalidates the prepared run. Freeze after content review and checks.
`content-review.json` must record the current fixture hash with reviewed status;
changing the content invalidates that review for dispatch.

**No new paid execution is authorized by these commands.** Immediately before
paid execution, obtain Justin's concrete smoke/formal allowance. The runner
requires both `ATLAS_BENCHMARK_PAID_CONFIRMATION=RUN-ATLAS-HETEROGENEOUS` and an
explicit `ATLAS_BENCHMARK_BUDGET_EUR`. The existing ceilings remain EUR 1 for
smoke and EUR 20 for formal; they are ceilings, not spending authorization.
Formal execution additionally retains its existing formal confirmation gate.
An offline manifest using a default ceiling is preparation, not authorization;
reprepare a new unfired campaign if the approved allowance differs.

The local Docker/Artemis services must be available before live preparation.
Keep inference disabled during standalone CRUD verification. Preserve partial
setup and observation records; investigate interrupted setup instead of blindly
recreating its courses. Never reuse the baseline's active-run or evidence files.

## Reporting and writing handoff

Report all 90 scheduled observations, failures, missing usage, type coverage,
and the complete four-stage bootstrap totals. Apply EUR 0.50 only to each
invocation. The heterogeneous campaign becomes the main thesis evaluation only
after execution and provider reconciliation; retain the original campaign as a
separate baseline. Type mix, content lengths, and unit count change together, so
the campaigns are descriptive comparisons, not an isolated causal type effect.

Until measured evidence exists, retain the existing thesis/presentation results.
After reconciliation, update evaluation, affected abstract/introduction/summary
claims, limitations, appendix, outline, and the presentation's setup/results/
conclusions. Preserve Keynote animations and notes, compile the relevant thesis
entrypoint, and inspect the exported thesis and slides visually.

## Request-size guard revision (13 September 2026)

The stopped v3 formal campaign retains 25 completed observations, one partial
bootstrap observation and 64 undispatched slots. Its conservative serialized-byte
input bound crossed the 272,000-token standard-pricing boundary; this is not a
measurement of the blocked request's token count. Do not replay that partial
observation or merge its evidence into a replacement campaign.

A fresh campaign may freeze verified full-context Luna pricing: a 1,050,000-token
context, a 272,000-token tier boundary, twice the input rates (including cache
reads and writes), and 1.5 times the output rate above the boundary. The uplift
applies to the whole request, based on actual provider input tokens. Standard
pricing manifests retain the old restricted behavior and are never rewritten.

With the full-context rates present, the ledger reserves using the conservative
byte bound capped at the verified physical input maximum, and includes the
highest applicable input/cache-write rate and maximum output allowance. This is
a spending bound, not a tokenizer or a claim that a large JSON request has that
many tokens. Actual usage must still fit verified model and reservation bounds;
missing usage retains its reservation. Model context rejection remains a recorded
provider failure. No prompts, native tool loop, callback caps or model settings
are changed by this accounting revision.

Replacement campaigns use fresh courses, identifiers, manifests, runtime hashes
and smoke validation. All previous attempts and unresolved reservations remain
visible and reduce the original authorized smoke/formal allowances.
