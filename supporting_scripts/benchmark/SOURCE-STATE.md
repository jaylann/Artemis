# Exact source state

Status: refreshed on 25 September 2026 with the PR fixes pushed after the 22 September integration. The earlier source `1bd8d1603e`/`a8d2d3e0f2` and its campaigns stay preserved, but they do not measure the final PR heads. See [Refresh of 25 September](#refresh-of-25-september).

## Develop

- Commit time: **2026-09-22 11:16:00 +02:00 (CEST)**.
- Commit: `b7d2a004e4e544cb82f08a50912fbeef595d31ad`.
- Subject: `Development: Let each push to develop start its CI run at once (#13931)`.

## PR heads integrated

| PR | Purpose | Exact head |
| --- | --- | --- |
| [#13579](https://github.com/ls1intum/Artemis/pull/13579) | AI provenance | `3c18b332e6189184b38d23639379d26c79986b47` |
| [#13600](https://github.com/ls1intum/Artemis/pull/13600) | Specialized workers | `e04fdb714d03a382473f5b6746838843d475d925` |
| [#13605](https://github.com/ls1intum/Artemis/pull/13605) | Lecture-unit orchestration | `b8d615d53b169a00aef8792ff1b28561e5a86aaa` |
| [#13908](https://github.com/ls1intum/Artemis/pull/13908) | Orchestration hardening | `bd403f23f7edec464ef1e05b6187bfefe7ccb951` |

Responses support from merged #13794 is already in develop (`4e8bb9333390a7024351b2f3fde5462875999112`). The PR deltas were applied in dependency order: workers → lecture units → provenance, then hardening. The exact patch bases are recorded in `../demo/integration.json`. All four patches applied without source conflicts. The consolidated Liquibase baseline, programming build-config integration, and optional LLM feature gating now come from the refreshed upstream stack, without reapplying the old local resolutions.

The updated PRs include directed competency relations in worker context, synchronized mutation evidence, lecture eligibility invalidation, lecture-link AI provenance, deletion guards, and explicit `NO_OP` results for verified runs without changes. The two flavor safeguards now come from **#13908**: restore the original text if edits leave it blank, and reject removal spans that are not uniquely identifiable (including overlapping or whitespace-equivalent matches). They are no longer benchmark-only additions. Flavor stripping uses Responses/high, orchestration xhigh, workers high, all Luna; the native callback budget remains 256 with wrap-up after 224.

## Refresh of 25 September

The 22 September integration used #13605 at `a0f3623` and #13908 at `f1d23ed`. Their later deltas were applied on top with `git apply --3way`, without conflicts:

- #13605 `a0f3623..b8d615d`:
  - `a53b151`: lecture-unit detail reads strip flavor text, and the course-scoped `searchLectureContent` tool is restored. It reports itself unavailable here because Iris is disabled.
  - `bc85f83`: initial preparation seeds the invocation cache with the content it has already extracted and stripped. The first detail read of a changed object therefore no longer repeats the flavor-strip request.
  - `b8d615d`: automatic summaries carry a batch-level `outcome` (`SUCCESS`, `PARTIAL`, `FAILED`).
- #13908 `f1d23ed..bd403f2`:
  - `bd403f2`: exercise detail reads strip flavor text again.

At `a8d2d3e0`, detail reads returned unstripped text. The 22 September campaigns therefore measured a different flavor-stripping and caching behavior. Every touched main source is byte-identical to `git merge-tree` of `b8d615d` and `bd403f2` (base `e04fdb7`). `OrchestratorReadToolsServiceTest` conflicts in that merge. The sequential apply keeps both boundary tests with detail stripping enabled. Records: `.demo/refresh-20260925/`.

Three review findings remained open on 25 September and are not included. None of them is on a path this fixture exercises:

- #13600, read errors are not worker terminal evidence. This only happens when every worker read fails.
- #13605, blank text units remain eligible. All fixture lecture units have non-blank text.
- #13908, the Iris command-ack `MESSAGE` frame is denied. Iris is disabled.

## Local changes beyond develop and the four PRs

Application/source differences are confined to:

- `HazelcastConfiguration.java`: retain the demo’s optional cluster-name override and standalone configured-port binding (seven added lines). These isolate the runtime.
- `ContentChangeSchedulerTest.java`: adapt four lines in the hardening regression to the lecture-aware `BatchClaim` and `runBatch` signatures. No scheduler behavior is changed.

Everything else added locally is outside the application:

- The copied `supporting_scripts/demo/` launcher, seed/content, Compose setup, API check and documentation; `.demo/` remains ignored. Its launcher allows Compose-project, Hazelcast-cluster and OpenAI-base-URL overrides.
- `supporting_scripts/benchmark/`: external request meter, sequential driver, retained 90-observation heterogeneous fixture/API seeder, native completion observer, decimal-cost reporter and offline verification. No Java instrumentation or custom benchmark application endpoints.
- Benchmark runtime settings select separate ports/resources and the localhost meter. The scheduler’s existing DEBUG log is enabled so automatic `NO_OP` completion can be measured despite the absence of a STOMP summary. The observer preserves the native result and verifies its run ID/batch size; it does not modify application behavior.
- `.gitattributes` preserves historical evidence bytes. `benchmark-results/` remains unchanged from fork commit `2f07475e4270da9b846668f283221c000848832c`.

Workspace: `/Users/justin/Programming/University/artemis2/.worktrees/atlas-cost-benchmark-20260922`.
The original `/Users/justin/Programming/University/artemis2/.worktrees/atlas-demo` was read only. `source-snapshot.json` records the initial copy, before both refreshes; `../demo/integration.json` records the current integration. The earlier larger benchmark workspace is retained separately and is not used here.

The source commit's parent is fork tip `2f07475e4270da9b846668f283221c000848832c`, separate from the implementation base above. Justin explicitly authorized the agent to commit and push, again for the 25 September refresh; each publication is an ordinary forward update to `jaylann/Artemis` branch `feat/atlas-benchmark-cap256-20260911`, with matching remote SHA. Paid execution requires the committed source SHA to match the fork. Subsequent harness fixes must also be published before use.

Live validation found two external API-observer mismatches: course configuration is nested in response DTOs (update DTO fields remain flat), and the simple STOMP broker does not send subscription receipts. The harness follows those existing contracts. No application behavior was changed.
