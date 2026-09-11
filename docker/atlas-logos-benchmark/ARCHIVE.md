# Archived benchmark implementation

This branch preserves the implementation used for campaign `atlas-logos-formal-20260911-cap256-v1`. Cite the full Git commit identifier when referencing this implementation; a branch name alone can move.

The campaign ran from a dirty worktree based on develop commit `3422402470fe63e055f7f7246ad6b8547a99fb9e`. The original checkout's committed and uncommitted application changes were reconciled onto that base, then the benchmark implementation was refined. The old temporary Responses loop was replaced. This archival commit was created after execution, without changing the measured implementation. The separate Responses prerequisite PR worktree is not the complete benchmark implementation.

The frozen campaign code fingerprint is `134ed13aca8ac75c01df47aaf0ff51c562b50e94dadd8b4b6abc4432bf5508c9`. It was recomputed and matched immediately before publication. It covers Atlas Java, core benchmark Java, Atlas prompts, runner-directory code, Docker configuration/code/prices, and the benchmark Spring profile. It is not a whole-repository hash. The Git commit additionally identifies the complete published source tree.

To recompute the campaign fingerprint without provider calls, from the repository root:

```sh
python3 - <<'PYTHON'
from pathlib import Path
import runpy
runner = runpy.run_path('scripts/atlas-logos-benchmark/run.py')
print(runner['code_fingerprint'](Path('docker/atlas-logos-benchmark/pricing.json').resolve()))
PYTHON
```

The shell entry point is `scripts/run-atlas-logos-benchmark.sh`. Read `PROTOCOL.md` for the experiment and its dated amendments; older sections in the preparation and smoke documents describe earlier configurations. The final configuration uses Luna for all phases, a shared 256-callback budget with wrap-up after 224, and disabled AtlasML. Logos is pinned to `0d4a6683171cd956e1cf46a19d49e99181a52ac4`; the scripts also accept an operator's own Logos instance.

Original campaign evidence remains under the ignored local directory `build/atlas-logos-benchmark/evidence/atlas-logos-formal-20260911-cap256-v1/`. Its original manifest retains the execution-time base commit; it must not be rewritten to pretend this archival commit existed before execution. Credentials, local databases, build products, and raw campaign journals are not included in this source branch. Code publication alone does not archive those empirical records.

The following SHA-256 values identify the separately retained evidence:

| File | SHA-256 |
| --- | --- |
| `manifest.json` | `1406256fd8092b7cff1136d36c010d898854339386f4bce6680c858909b5aa04` |
| `runtime-sha256.json` | `f76707ed72ec61d30aee650dba074f9c42a86ab40136cf6ef3641fb4532c52a7` |
| `fixture-definition.json` | `f1c6d1836fb5d434f223105b24206976dde66e58444f0c9cac8a9bc24036d66f` |
| `schedule.json` | `60dc241d97cd0a6eef5c3c55ee8168accac3070d3a1da8bb7f332a2997ba79cd` |
| `pricing.json` | `34f32cb4751213de65f41b078a81a74cdecec5ed17af46687ea9458f872b7af8` |
| `report.json` | `08ee82f88b1a06719f3da5fd9118e5d5f61de147c5a3c9d77f626d21e441d3ec` |
| `observations.csv` | `aae53e27f2254b378c05b4e375eb5b516c59721b9bdc18d3579dd8f7771dd71f` |

Existing verification and campaign evidence were reused for publication. No application tests or paid execution were repeated. The archival commit preserves the source bytes rather than applying a fresh formatting pass.

A scoped `.gitattributes` entry preserves the existing report-template CSV line endings during Git storage and checkout. This publication metadata does not alter the measured application.
