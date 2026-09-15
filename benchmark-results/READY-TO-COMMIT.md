# Archive publication scope

Branch: `feat/atlas-benchmark-cap256-20260911` in the existing benchmark worktree.
Fork: <https://github.com/jaylann/Artemis>.
Current local and remote HEAD: `68f8023db3126c3ea9c9e8b3bf75231118525e15`.

Justin explicitly authorized committing and pushing this package to his fork on
15 September 2026. This document preserves the exact publication scope. See
[verification](VERIFICATION.md) for evidence, build and offline reproduction checks.

## Commit scope

- `.gitattributes`: preserve archive bytes without line-ending conversion.
- `benchmark-results/`: immutable heterogeneous data, separate text-only comparison,
  source/runtime fingerprints, provenance, checksums and reproduction instructions.
- `tools/atlas-benchmark-analysis/`: standalone Decimal analysis, pinned figure
  dependencies, failure tests, and portable build-classpath helper.
- `scripts/atlas-logos-benchmark/`: existing uncommitted heterogeneous fixture,
  seeding, integrity/extraction validation, runner/report updates and tests.
- `src/main/java/de/tum/cit/aet/artemis/core/benchmark/AtlasLogosBenchmark.java`:
  measured pricing/tier support changes.
- `src/test/java/de/tum/cit/aet/artemis/core/benchmark/AtlasLogosBenchmarkTest.java`:
  associated pricing tests.

Exclude the unrelated existing edit to
`src/main/webapp/app/programming/manage/detail/programming-exercise-detail.component.ts`.
Also exclude ignored runtime builds, environments, raw private backups and caches.
The complete included file list is `ready-files.json` in this directory.

## Review and publication

Publish only this scope using the normal repository checks. Push to
`jaylann/Artemis` on the named branch, not the `origin` remote if it points to
`ls1intum/Artemis`. Preserve the original manifests' execution commit and dirty-source
provenance. The new commit is a later archive publication, not the measured execution.

After pushing, confirm the remote commit and reproduce the calculations from a clean
checkout using [README.md](README.md). Remote publication verification is recorded in the thesis evaluation archive. Thesis and native Keynote edits live separately in the thesis
repository and are not part of this Artemis commit.
