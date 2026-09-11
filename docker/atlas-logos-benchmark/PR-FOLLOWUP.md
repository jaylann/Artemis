# Atlas-only Responses PR preparation

Justin requested this follow-up on 2026-09-10. No PR, commit, or push has been created.

Propose the optional Atlas Responses ChatModel and its configuration, delegation wiring, and tests as a normal Artemis capability. Explain the observed OpenAI rejection of Luna function tools with reasoning on Chat Completions. Enable Responses and Luna for autonomous Atlas operations while preserving the shared client used by interactive Atlas and Hyperion. Spring AI remains responsible for tool execution; the adapter translates requests and retains reasoning state.

Before opening the PR: complete live multi-round/worker smoke through Logos; verify provider-model-reasoning compatibility, reasoning replay, cancellation, missing usage, retry accounting, and provider configuration isolation. Document the synchronous/text/function-tool scope and replace the adapter once native Spring AI Responses support is suitable. Keep thesis fixtures, cost-report tooling, and private smoke evidence out of that upstream transport PR unless requested separately.

Update the canonical thesis protocol to the validated transport before formal execution. The existing failed Chat Completions smoke and its unresolved cost reservation must remain preserved; no new campaign directory resets the original spending allowance.

A separate worktree based on fresh `develop` is prepared at `.worktrees/atlas-responses-luna-pr`, branch `feat/atlas-responses-luna`. Develop does not yet contain the nested-worker framework; that framework is excluded from this transport change. Justin must create the commit and open the PR under the repository no-commit rule.

The ignored draft PR description is `build/atlas-responses-pr/PR.md` inside that separate worktree; its verification log is beside it. The benchmark worktree passed real Responses verification on 10 September 2026, including nested workers and both trigger paths. The separate PR worktree has offline adapter verification and has not been deployed separately.

The 11 September shared tool-budget correction is applied in both worktrees: 128 callbacks for the whole autonomous run, preserved partial changes/usage, a typed terminal failure, and no scheduler replay on exhaustion. The PR worktree uses its existing flat autonomous tool surface; the benchmark also propagates the budget into nested workers. Earlier live evidence does not validate this revised limit.

The graceful revision is now also in that Responses worktree: model-visible budget notices, verification-only mode after 96 callbacks, the shared 128 cap, batch-scoped planning, and invocation-local extraction reuse. It uses the same native Spring AI advisor. The develop-based branch still uses flat autonomous tools; the benchmark additionally exercises nested workers. Local checks and the new rehearsal are recorded separately from the earlier live evidence.

The subsequent user-requested increase is applied in both worktrees: 256 total callbacks, verification-only after 224, preserving the 32-call finishing reserve. The new limit is tested and frozen independently from the preceding 128-cap live evidence.
