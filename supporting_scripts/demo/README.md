# Atlas integration demo

This copied workspace has been refreshed for the cost benchmark. Current source heads and local adaptations are in [the benchmark source record](../benchmark/SOURCE-STATE.md); the dated demo handoff below describes the original snapshot. Use `../benchmark/server.sh` for the isolated benchmark runtime.

This worktree combines PRs #13579, #13600, #13605, and #13908 on fresh develop, without creating Artemis source commits. Exact patch bases and heads are recorded in `integration.json`.

## Run

Requires Docker, Java 25, and the Node/pnpm versions pinned by the repository. All commands run from the worktree root.

```bash
supporting_scripts/demo/run-demo.sh build
supporting_scripts/demo/run-demo.sh start
supporting_scripts/demo/run-demo.sh seed
supporting_scripts/demo/run-demo.sh verify
node supporting_scripts/demo/check-openai.mjs
supporting_scripts/demo/run-demo.sh stop
```

Open http://localhost:8082 and log in with `artemis_admin` / `artemis_admin`. Set `DEMO_USER` and `DEMO_PASSWORD` consistently for startup and seeding to override these local defaults. Stopping retains the database; starting resumes it. `build` must be rerun after source changes.

The database uses port 55432; local Git SSH uses 7923; Hazelcast uses 5703. Override with `DEMO_DB_PORT`, `DEMO_HTTP_PORT`, `DEMO_SSH_PORT`, and `DEMO_HAZELCAST_PORT`. Direct seeder invocation accepts `DEMO_URL`. Existing listeners are never stopped. Local CI runs one build and one result processor at a time. Runtime files, logs, repositories, and the seed manifest live in ignored `.demo/`; PostgreSQL uses its own Compose volume.

## OpenAI

Atlas connects directly to `https://api.openai.com/v1`; no Logos instance is required. Set `OPENAI_API_KEY` or put only that variable in `.demo/openai.env` (file permissions 600). Never commit that file. The launcher uses `gpt-5.6-luna` through the Responses API for orchestration and workers, and `gpt-5.4-mini` for interactive chat. Override using `DEMO_ATLAS_MODEL` and `DEMO_CHAT_MODEL`.

The AtlasAgent feature is enabled. Both courses start with automatic orchestration disabled and no competencies. Manual Atlas actions make real, billable OpenAI requests and can change course content. Enable automatic orchestration in the course settings when wanted. Hyperion is enabled against the same OpenAI configuration so the client’s variant-job tray works. AtlasML and Iris remain disabled.

## Dataset and repeatability

Each batch creates two matching courses with the same dataset: two programming, two quiz, two modeling, two text, and two file-upload exercises; three lectures, each with two text units. There are no seeded students, competencies, submissions, grades, exams, or discussion posts. Programming setup creates the base template, solution, and test repositories required by Artemis.

To create another pair at any time while the server is running:

```bash
# Every invocation creates a fresh pair with a generated batch name.
supporting_scripts/demo/run-demo.sh new

# Or pick a memorable batch name; repeating this command resumes the same pair.
supporting_scripts/demo/run-demo.sh seed --batch workshop-1
supporting_scripts/demo/run-demo.sh verify --batch workshop-1
```

Named courses appear as **Software Engineering — workshop-1 — A** and **Software Engineering — workshop-1 — B**. Batch names allow 1–32 lowercase letters, digits, or hyphens, beginning with a letter or digit. `new --batch workshop-1` also works, but deliberately fails if that batch's manifest already exists. `seed` and `verify` without a batch retain the original **Software Engineering — A/B** pair; `default` is reserved for it. Use `node supporting_scripts/demo/seed-demo.mjs --help` for CLI help.

Each batch saves its shared date anchor, semester, course identifiers, and creation progress in ignored `.demo/seeds/<batch>/seed-manifest.json`. The original pair keeps `.demo/seed-manifest.json`. New pairs use the current date for their schedule; existing pairs keep their original dates. The command prints the batch name, course links, and exact resume command. After an interrupted `new`, use that printed `seed --batch ...` command to finish the pair; another `new` would start a separate pair.

Reruns reuse entities, complete unfinished creation, and verify content; they do not overwrite instructor edits. Keep the manifests alongside the database. A replaced database with an old manifest, or existing course identifiers without their manifest, fails explicitly. Creating more pairs requires no database reset and does not change other pairs. The seeder serializes runs with a worktree-wide lock.

### Update the educational content

The dataset uses a university-library and campus-bookstore case study: pickup queues, supplier imports, circulation history, lending policies, concurrent reservations, and payment reconciliation. Task sheets live in `content/*.md`; quiz questions, answer explanations, instructor guidance, and lecture text live in `course-data.mjs`. Programming contracts match the generated repositories. Both quizzes contain four scenario questions worth 2.5 points each.

After editing that canonical content, explicitly refresh an existing pair:

```bash
supporting_scripts/demo/run-demo.sh seed --refresh-content
supporting_scripts/demo/run-demo.sh seed --batch workshop-1 --refresh-content
```

Refresh updates the manifest-owned exercises in place, including renamed titles, task Markdown, quiz questions, difficulty, example solutions, and grading guidance. It also refreshes lecture text. It preserves exercise IDs, course dates, and programming repositories. Each course's previous exercise and lecture responses are backed up as a private `content-backup-*.json` beside its manifest before updates. The operation replaces instructor edits to those content fields, so use the flag deliberately. An uneditable quiz stops the refresh before that course is changed. Failed updates report the error and can be resumed with the same refresh command. Matching content is not written again. Ordinary `seed` continues to check existing content without overwriting it.

Verification checks only the selected pair: counts, exercise and lecture content, repository availability, absence of competencies, and equality between normalized course datasets. Other courses may coexist. After experimenting with Atlas, the absence-of-competencies assertion will intentionally fail. `verification.json` beside each batch manifest records its latest successful verification. Seeding uses authenticated local Artemis APIs and does not call OpenAI or automatically run Atlas orchestration.

Exercise repository commits made by the application are confined to the demo's local VCS. The Artemis source branch remains uncommitted and is never pushed.

## Verified handoff (2026-09-21)

- Fresh develop: `6d7f286184ec6546ba15beac26dd21600a498fba`; all four PR source heads are in `integration.json`.
- Java main/test compilation, Checkstyle, production Angular build, and runnable WAR packaging passed. Focused Java cases passed after adapting the scheduler fixture to the combined lecture/hardening contract; 99 selected Vitest tests passed.
- Fresh isolated database migration and local startup succeeded. Repeated seeding preserved exactly 2 courses, 20 exercises, 6 lectures, 12 text units, 1 administrator, 0 competencies, and 0 discussion posts.
- API verification compared course settings and all educational content. All four programming solution builds scored 100%: 13/13 sorting tests and 6/6 collection tests per course. Empty student starter implementations are expected to fail their tests.
- Desktop Chrome verification covered both courses, every exercise type, lecture management, quiz solutions, and expanded lecture text. The final browser run had no console errors or failed HTTP requests. Temporary screenshots and navigation evidence are in `/tmp/atlas-demo-ui/`.
- A real `gpt-5.6-luna` Responses request and a real Atlas chat call through Artemis succeeded. Automatic orchestration remains disabled; the smoke test did not modify competencies. Full mutation-producing orchestration runs and the complete repository test suite were not executed.
- Runtime verification records are in ignored `.demo/verification.json` and `.demo/openai-verification.json`. Credentials remain solely in ignored `.demo/openai.env` with mode 600.

Integration adaptations preserve develop's consolidated Liquibase baseline, explicit programming build configuration, and optional Atlas LLM bean gating. `spring.hazelcast.cluster-name` is an optional isolation override; standalone Hazelcast now honors its configured port. This demo also serializes local CI result processing to avoid the concurrent test-case insertion deadlock encountered during initial seeding.
