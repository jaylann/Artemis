# Provider-Cost Characterization Protocol

Chapter 6 characterizes provider usage and cost for bounded autonomous competency maintenance in
Artemis. It tests a supervisor-approved EUR 0.50 per-invocation criterion under a fixed synthetic
fixture and fixed provider configuration. It is not a semantic, pedagogical, student-outcome,
production-cost, production-throughput, or architecture-comparison evaluation.

## Fixture and conditions

Use one fixed synthetic fixture with 60 text exercises, 12 reference competencies, and one text
lecture unit. Instantiate a separate isolated Artemis course for every condition and repetition.
The fixture uses the same 12 reference topics in every course. The six primary conditions are:

- **Maintained:** all 60 exercises have existing links, which remain present, and 12 linked exercises
  receive semantic-unchanged edits.
- **Assignment:** 12 anchor exercises are unlinked; 12 competencies and links from 48 background
  exercises already exist; stage one assigns the anchors.
- **Mixed:** 6 competencies and their 24 background links exist, the other 6 competencies are
  absent, and 12 anchor exercises are unlinked; stage one exercises creation and assignment paths.
- **Bootstrap:** 0 competencies and 60 unlinked exercises exist; stage one processes the first 12
  exercises, and continuation stages process the remaining 48 exercises in bounded 24-exercise
  batches subject to the shared 256-attempted-callback cap; a continuation may terminate when that cap is reached.
- **Single-exercise maintenance:** one linked exercise receives a semantic update through the normal
  manual Artemis entry point.
- **Lecture-unit maintenance:** the linked text lecture unit receives a semantic update through the
  normal automatic content-change path.

Run each primary condition 10 times. Independently randomize the order of the six conditions within
each repetition using a recorded order seed, then execute that order sequentially. This produces
10 x 6 = 60 primary invocations. Run both bounded 24-exercise bootstrap continuations for every bootstrap
course, producing 20 additional planned observations and 80 planned observations in total. Do not
retry, substitute, or replace an observation. If a continuation prerequisite fails, preserve the
planned continuation as not executed with its reason.

## Normal execution path and safety boundary

Formal observations use the normal Artemis application path. Manual observations enter through the
ordinary manual orchestration entry point. Automatic observations travel from the content event to
the course-scoped accumulator and scheduler, then converge on the same orchestration service. Both
paths use Spring AI, the locally deployed official Logos service, and its OpenAI upstream. The
benchmark implementation resides in the Artemis repository with a shell-script entry point and
reuses the existing Logos URL and key configuration; a local Logos stack is optional for reuse. The thesis
campaign remains planned against the local official Logos deployment described here. The campaign
uses the normal Atlas entry points with an optional Atlas-only Responses ChatModel adapter through Logos; Spring AI still owns the tool loop. No benchmark-specific application endpoint is added. AtlasML is disabled. The adapter revision requires a new frozen manifest and successful smoke before formal execution.

Set automatic debounce to five seconds only for local execution expediency. Record event-to-completion delay
separately from orchestration execution time. Their difference includes scheduling overhead as well as debounce, so do not interpret this local
value as a production setting or throughput evidence. Keep automatic scheduling and unrelated
development data disabled.

Fix and record fingerprints for the source revision, Artemis and Logos application revisions,
prompt templates, tool implementation sources, model and reasoning configuration, fixture definition and seed,
condition-order seed, and dated pricing manifest. Use `gpt-5.6-luna` with `xhigh` reasoning for the
orchestrator, `gpt-5.6-luna` with `high` reasoning for workers, and `gpt-5.6-luna` with `medium`
reasoning for flavor stripping. Record exact requested and returned model identifiers.

The campaign has a EUR 1.00 smoke allowance for local infrastructure validation. The original EUR 1.00 smoke allowance remains separate from the EUR 20.00 formal allowance authorized on 10 September 2026. It is separate from formal observations. Formal execution was authorized on 10 September 2026; a smoke attempt cannot be reported as formal evidence.

The campaign does not retry an observation, substitute a course, or replace a failed run. Provider
or SDK retries inside an invocation remain part of that invocation. Record every HTTP attempt and
include every attempt in its invocation cost. A technical prerequisite failure can prevent a
dependent stage from executing; preserve that planned observation and label it accordingly.

## Cost acceptance criterion

Define one run as one orchestrator invocation and one scheduled observation. Sum all provider phases
and all provider or SDK attempts belonging to that invocation. Each planned observation must have
measurable total provider cost not exceeding EUR 0.50. Any measured exceedance fails the criterion.
Missing or unsupported cost evidence makes the overall conclusion incomplete rather than
successful. Report the maximum invocation cost, its margin to EUR 0.50, and the resulting pass,
fail, or incomplete conclusion. The threshold applies only to this fixed protocol; it is neither a
production service-level agreement nor a host-enforced spending limit.

Report the complete three-stage bootstrap cost descriptively. Do not compare that aggregate with
the per-invocation threshold. Keep the smoke allowance and the formal dispatch budget separate from
the acceptance criterion.

## Measurements and cost model

Passively capture every provider HTTP attempt and reconcile the Artemis usage trace with the
corresponding local Logos record. For each attempt, record the campaign and observation IDs,
condition, repetition, stage, invocation and attempt IDs, phase, provider, requested and returned
model IDs, provider response ID, timestamps, execution duration, HTTP status, terminal status,
retryability, usage availability, token counts, and fixed fingerprints. Attribute flavor stripping,
main orchestration, each worker delegation, correction stages, and context construction or reads.
Record reads, delegations, corrections, write attempts, and the technical state and trajectory
oracle as operational context.

Reconcile identifiers without retaining raw prompts or tool arguments. Store request and response
hashes and bounded byte counts where permitted. Provider `inputTokens` is total input. Cached input
and cache-write input are disjoint subsets of total input. Reject an attempt unless both are nonnegative and
`cached_input_tokens + cache_write_input_tokens <= input_tokens`, then derive
`uncached_input_tokens = input_tokens - cached_input_tokens - cache_write_input_tokens`. Missing usage remains unknown and
never becomes zero.

Read the independently verified model prices and USD-to-EUR conversion from the dated campaign
manifest. For each attempt, apply the manifest's rates:

`cost_usd = rate_uncached * uncached_input_tokens / 1e6 + rate_cached * cached_input_tokens / 1e6 + rate_cache_write * cache_write_input_tokens / 1e6 + rate_output * output_tokens / 1e6`

`cost_eur = cost_usd / usd_per_eur`

For Luna, cache writes use 1.25 times the uncached input rate, as verified in the [OpenAI caching guide](https://developers.openai.com/api/docs/guides/prompt-caching) on 2026-09-10. The dispatch bound reserves all potential input at the higher write rate.

If an attempt requires long-context or cache-write pricing outside the manifest's supported scope,
mark its cost unsupported and the observation invalid/unmeasured. Do not apply a standard rate by
assumption. Report provider usage only; do not add offline or counterfactual prompt estimates.

The technical oracle checks course isolation, object ownership, event and invocation identity, the
recorded state transition, and evidence continuity across Artemis and Logos. It confirms technical
interpretability only. It does not require fixed expected model mutation counts and does not assess
the semantics or pedagogical suitability of a competency or link.

## Observation labels and completeness

Label every planned observation as one of:

- **Valid completed:** terminal success or no-op, complete usage and model evidence, intact
  Artemis-to-Logos trajectory, and a passing technical oracle.
- **Valid partial:** bounded wrap-up with unresolved work, complete usage, and intact evidence. Retain applied changes and report separately from completed observations.
- **Infrastructure failure:** setup, trigger, state-read, or cleanup failure. Preserve any underlying model outcome and cost; stop when isolation cannot be established.
- **Valid failure:** terminal failure with complete usage and model evidence and an intact
  trajectory. Report its tokens, latency, and cost separately as failure-path observations.
- **Invalid/unmeasured:** missing usage or model identity, unsupported pricing, missing evidence,
  changed fingerprints, broken trajectory, or a prerequisite failure that prevented execution.
  Preserve the record and reason while excluding it from the relevant primary distributions.

Dataset completeness and cost acceptance answer separate questions. The dataset is complete only
when all 80 planned observations have condition, repetition, stage, label, and evidence metadata.
The cost criterion passes only when every planned observation has measurable total provider cost and
none exceeds EUR 0.50. An invalid or unmeasured observation leaves the overall conclusion
incomplete. Never turn a technical oracle result into semantic or pedagogical correctness evidence.

## Reporting

Report all 80 planned observations, separating the 60 primary observations from the 20 bootstrap
continuations. Include condition and stage coverage, labels, prerequisite-dependent non-execution,
terminal failures, provider responses, model identities, token counts, phase attribution, reads,
delegations, corrections, write attempts, provider or SDK retries, execution latency, debounce wait,
and cost. Report the median, range, and maximum individual invocation cost, the maximum's margin to
EUR 0.50, and the pass, fail, or
incomplete conclusion. Primary cost distributions include valid completed observations; valid
failures receive a separate failure-path summary; invalid/unmeasured observations remain visible in
a completeness table. Report the bootstrap aggregate separately and descriptively.

Discussion may interpret provider-cost patterns, phase attribution, model-specific usage, and the
manual or automatic path only within this fixed protocol. It must not claim semantic or pedagogical
quality, production affordability or throughput, student outcomes, causal benefits of orchestration,
workers, flavor stripping, or context size, architecture superiority, or production effectiveness.

Limitations must state the supervisor-defined threshold, synthetic fixture, six conditions, ten
repetitions per primary condition, sequential randomized schedule, dependence between bootstrap stages, local debounce, one provider/model configuration, dated pricing, stochastic model
behavior, fixed fingerprints, disabled AtlasML, local official Logos path, technical-oracle scope,
unsupported pricing cases, and any invalid or incomplete observations. State explicitly that the
protocol does not establish production cost, throughput, effectiveness, student outcomes,
semantic or pedagogical quality, or generalizable architecture benefits.

## Revised recovery policy, 10 September 2026

After the interrupted launch and subsequent timeout stopped the first formal campaign, Justin authorized a fresh campaign and revised transport-failure handling. The old campaign remains an abandoned operational attempt, with its observations, provider usage, and reservations preserved. It is excluded from the replacement campaign's primary distributions. This decision precedes all replacement observations and applies to the whole abandoned campaign, not selected results.

The replacement campaign retains 60 primary observations and 20 continuations on fresh courses. Native SDK retries handle recoverable provider failures at the HTTP request boundary. Every attempt has its own recorded ID, retry index when supplied by the SDK, usage, status, duration, and reservation. The runner never replays an entire partially mutating orchestration to obtain a successful outcome. Exhausted request retries can result in an application fallback or terminal failure, both retained as observed.

A captured timeout with missing usage leaves its cost unknown and its conservative reservation held. That hold counts against the same allowance during later observations and after restart. It does not by itself cancel independent observations. An uncaptured dispatch reservation after a crash, a pricing/bound violation, exhausted budget, or failure to disable course automation still stops dispatch. Operator interruption preserves remaining observations as pending. Failed bootstrap prerequisites still skip dependent stages with the reason.

Known cost and outstanding conservative reservations from the abandoned formal campaign reduce the replacement campaign's available allowance below EUR 20. The original smoke allowance remains separate. The carry-forward artifact links the old journals and records the exact remaining cap before freeze. Unknown costs cannot become zero, even if a later retry succeeds. The report exposes known measured cost, unknown attempt counts, retained reservations, and native retry attempt counts separately from total cost. A total with incomplete usage remains unknown and cannot pass the EUR 0.50 criterion.

The CLI prints start/end status, ten-second progress, and a stop reason. These diagnostics read the passive evidence and do not change prompts, tools, responses, or model-selected actions. Ctrl-C requests a stop and lets bounded course cleanup finish. A second Ctrl-C during cleanup does not abort that cleanup.

Before the replacement freeze, Justin requested Luna for every model phase. Orchestration uses xhigh, workers high, and flavor stripping medium. The explicit request timeout is five minutes with three native retries (up to four HTTP attempts). Historical Mini calls remain in their original evidence and are excluded from the new campaign.

## Post-campaign tool-budget revision (11 September 2026)

The completed recovery-v2 campaign above used the earlier write-only cap plus Spring AI’s native limits. Its evidence remains unchanged. The revised implementation allows 128 attempted tool callbacks in total across each top-level run and its workers; reads, writes, delegation, failure, and completion all consume slots. It blocks callback 129 before execution, records a typed terminal limit outcome with partial changes, and prevents scheduler replay. Provider attempts and cost remain separate measurements. A future campaign must freeze this revised code and prompts separately and validate them with smoke execution before collecting observations; do not combine its results with the earlier campaign as one configuration.

## Graceful wrap-up revision (128-call configuration, superseded)

This configuration is frozen separately from the stopped tool-budget-v1 campaign. One invocation shares 128 attempted callbacks across orchestrator and workers. Every tool result includes the current count. At 96 calls the instruction changes to verification and completion; subsequent new writes and delegations are rejected before execution. Reads and completion remain available, and the final slot is reserved for completion. Failed and rejected callbacks consume slots. A model that continues after exhaustion receives a terminal budget outcome with a deterministic partial summary; there is no unbudgeted final provider call or automatic replay. Budget exhaustion and blocked work cannot be relabeled as success merely from model text.

The target is the changed batch. Unrelated unlinked exercises remain background context. Maintained, single-exercise, and lecture conditions now link all 60 background/anchor exercises before execution. Extracted learning content is reused only within its invocation; competency and mapping reads remain fresh. These prompt, fixture, and execution changes affect model behavior and cost, so earlier results must not be pooled with this configuration or treated as causal evidence for one individual change.

The terminal evidence includes application status, failure reason, authoritative callback count, tool names, hashed arguments, role, and blocked/failed callback outcomes. Partial model execution and infrastructure failure are reported separately. Read-only instructor API calls can retry bounded transient failures. Automation updates use state read-back after uncertain writes; the runner never retries an orchestration trigger. Cleanup uncertainty stops further dispatch.

Before formal launch, one smoke repetition covers all six conditions plus both bootstrap continuations (eight planned observations), using `ATLAS_BENCHMARK_REHEARSAL=1`. All must complete with reconciled evidence and technical isolation before readiness is declared. This operational rehearsal is excluded from formal results; its cost reduces the existing EUR 1 smoke allowance. Old measured costs and unknown holds reduce the remaining smoke and formal allowances rather than resetting them.

## Active configuration: 256-call cap (11 September 2026)

Before continuing the formal experiment, Justin requested a higher shared cap. The new frozen configuration permits 256 attempted callbacks per invocation across orchestrator and workers. The final 32 remain reserved for verification/completion: new writes and delegations stop after callback 224, and the final callback is reserved for completion. Every callback response exposes the current budget. All other condition, scheduling, measurement, retry, and isolation rules remain unchanged.

The 128-call formal campaign was drained at an invocation boundary after eight completed observations. No in-flight model request was interrupted. Its eight observations, 72 pending slots, cost, and evidence remain preserved separately. The 256-call campaign uses fresh courses and a separately frozen manifest; do not pool configurations. Earlier measured spending and unknown holds still count against the original allowances. The higher cap permits more work; it is neither a guarantee of completion nor a fixed provider-cost bound.

For the 256-call restart, Justin explicitly requested no further tests or repeated rehearsal and immediate benchmark preparation. The completed eight-observation 128-call rehearsal remains evidence for the unchanged transport, triggers, isolation, and measurement path. It is not claimed as live validation of 256-call exhaustion. No new paid smoke is dispatched for this restart; the new formal campaign records the higher cap from its first observation.
