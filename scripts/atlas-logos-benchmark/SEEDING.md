# Seed a single heterogeneous course

With the isolated Artemis backend running on localhost:8083:

```sh
python3 scripts/atlas-logos-benchmark/seed.py \
  --state-dir build/atlas-logos-benchmark/seed-heterogeneous-20260913
```

The command creates one course with 60 exercises, 12 lecture units, 12 reference
competencies, and all 72 intended links. Course automation stays disabled. It
never invokes Atlas or authorizes provider spending. It uses the reviewed fixture
and ordinary type-specific APIs. `ARTEMIS_USERNAME` and `ARTEMIS_PASSWORD` override
the repository's default local development administrator credentials.

Completed creations are checkpointed. Running the same command again reads back
the same course and validates content, quiz answers, ownership, and mappings;
it does not create a second course. An interrupted or failed write is retained
as unresolved. Inspect the recorded response and persisted course before resolving
it; do not delete the state file to bypass an uncertain write.

Programming exercises use normal Java template setup. The empty-repository API
option requires Hyperion, so it is not used. Stock Java repositories are not
solutions to the authored tasks. The Atlas extractor sees the problem statements;
student submission/CI correctness is outside this course's benchmark purpose.

The reusable seed is a content/CRUD preparation artifact, not a measured campaign.
Formal evaluation still needs fresh isolated condition courses, a reviewed
runtime/configuration freeze, live event checks, and an explicitly approved paid
budget. Do not use this exploratory course as a replacement formal observation.

After reviewing a changed fixture, refresh the existing exploratory course with
`--refresh-content`. The command archives its previous seed state, keeps course
automation off, updates only differing content through ordinary APIs, and verifies
all content and links before recording the new fixture hash. It never edits
repository code. Preserve the state directory and its failure records.
