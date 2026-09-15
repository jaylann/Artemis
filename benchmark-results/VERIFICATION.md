# Verification, 15 September 2026

## Evidence and offline calculations

- Heterogeneous: 90 observations, 2,639 attempts, 384 passed audit checks, 271
  checksum-covered files plus the checksum index, and 249 source fingerprint entries.
- Text-only comparison: 80 observations, 2,884 attempts, 344 passed checks, 256
  checksum-covered files plus the index, and 238 source fingerprint entries.
- All attempt identities, Logos usage, reservation/settlement pairs and reported
  invocation costs reconcile using Decimal precision 28. Maximum difference from
  the original report is EUR 3e-29 for the new campaign and EUR 1e-29 for the old.
- A clean temporary copy regenerated all ten published JSON/CSV/Typst/SVG outputs
  byte-identically while macOS sandbox rules denied all network access. Installed
  dependencies were available locally. No services or provider inference were used.
- Nine analysis tests passed, including explicit failures for missing application
  or gateway usage, duplicate identity, corrupted evidence, unsupported model or
  pricing, negative price, and invalid cache bounds.
- Frozen manifests and raw journals are unchanged. The new source fingerprint
  includes the extra selected pricing path. The original 398-file evidence index
  and 5,671-file measured runtime inventory were also verified locally during the
  writing audit. Runtime binaries remain private and are not needed for analysis.
- Archive inspection found no credential files, database/storage backups, private
  keys, or matches for common provider/GitHub secret-token patterns. Source code
  retains environment-variable names and public development configuration.

## Setup and implementation checks

- All 46 Python harness tests passed.
- Offline formal preparation generated a fresh 90-position schedule. Its live CRUD
  prerequisite remained explicitly unverified because no services were invoked.
- Java 25 and the repository Gradle 9.7.1 wrapper compiled the current source and
  generated a fresh runtime classpath with the provided init script.
- All 26 focused `AtlasLogosBenchmarkTest` cases passed.
- The DTO/extraction check passed all 144 cases against both the retained measured
  runtime and the freshly rebuilt classes, without database writes or provider calls.
- The initial offline build could not find every dependency in the local cache.
  The normal build downloaded the missing dependencies and succeeded. Build output
  included existing Java deprecation/unchecked and Gradle deprecation warnings.
- Scoped tracked-file whitespace checks passed. Frozen archived bytes are not
  reformatted to satisfy style checks. The unrelated web-client edit is excluded.

These checks reproduce published calculations and validate the build/setup path.
They do not rerun the stochastic experiment, prove a clean-machine service startup,
assess mapping quality, or settle missing usage in earlier failed campaigns.

## Publication

Publication to `jaylann/Artemis` branch `feat/atlas-benchmark-cap256-20260911` was
explicitly authorized by Justin on 15 September 2026. The archive extends base
commit `68f8023db3126c3ea9c9e8b3bf75231118525e15`. The original execution manifests
remain unchanged. The unrelated web-client edit is excluded from publication.

After publication, verify the remote commit and run the documented offline analyzer
from a fresh checkout of that commit. Publication verification is recorded with the
thesis evaluation's `remote-publication.json`.
