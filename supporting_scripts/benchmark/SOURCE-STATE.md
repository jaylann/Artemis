# Exact source state

Status: source published at `1bd8d1603e2ede31083cf9cbce3f244bbc250712`; external harness live validation is in progress. The initial smoke stopped before any provider request; its incomplete evidence is preserved.

## Develop

- Commit time: **2026-09-22 11:16:00 +02:00 (CEST)**.
- Commit: `b7d2a004e4e544cb82f08a50912fbeef595d31ad`.
- Subject: `Development: Let each push to develop start its CI run at once (#13931)`.

## PR heads integrated

| PR | Purpose | Exact head |
| --- | --- | --- |
| [#13579](https://github.com/ls1intum/Artemis/pull/13579) | AI provenance | `3c18b332e6189184b38d23639379d26c79986b47` |
| [#13600](https://github.com/ls1intum/Artemis/pull/13600) | Specialized workers | `e04fdb714d03a382473f5b6746838843d475d925` |
| [#13605](https://github.com/ls1intum/Artemis/pull/13605) | Lecture-unit orchestration | `a0f3623ab3273c7dc6339f20c26e6c43d14fc4e1` |
| [#13908](https://github.com/ls1intum/Artemis/pull/13908) | Orchestration hardening | `f1d23ed744e0f94feabe199c7aa2cbe894c131ed` |

Responses support from merged #13794 is already in develop (`4e8bb9333390a7024351b2f3fde5462875999112`). The PR deltas were applied in dependency order: workers → lecture units → provenance, then hardening. The exact patch bases are recorded in `../demo/integration.json`. All four patches applied without source conflicts. The consolidated Liquibase baseline, programming build-config integration, and optional LLM feature gating now come from the refreshed upstream stack, without reapplying the old local resolutions.

The updated PRs include directed competency relations in worker context, synchronized mutation evidence, lecture eligibility invalidation, lecture-link AI provenance, deletion guards, and explicit `NO_OP` results for verified runs without changes. The two flavor safeguards now come from **#13908**: restore the original text if edits leave it blank, and reject removal spans that are not uniquely identifiable (including overlapping or whitespace-equivalent matches). They are no longer benchmark-only additions. Flavor stripping uses Responses/high, orchestration xhigh, workers high, all Luna; the native callback budget remains 256 with wrap-up after 224.

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
The original `/Users/justin/Programming/University/artemis2/.worktrees/atlas-demo` was read only. `source-snapshot.json` records the initial copy, before this refresh; `../demo/integration.json` records the current integration. The earlier larger benchmark workspace is retained separately and is not used here.

The source commit's parent is fork tip `2f07475e4270da9b846668f283221c000848832c`, separate from the implementation base above. Justin explicitly authorized the agent to commit and push; publication was an ordinary forward update to `jaylann/Artemis` branch `feat/atlas-benchmark-cap256-20260911`, with matching remote SHA. Paid execution requires the committed source SHA to match the fork. Subsequent harness fixes must also be published before use.

Live validation found two external API-observer mismatches: course configuration is nested in response DTOs (update DTO fields remain flat), and the simple STOMP broker does not send subscription receipts. The harness follows those existing contracts. No application behavior was changed.
