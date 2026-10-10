# Synthetic Case packages and immutable input

`demo-data/` contains public deterministic fixtures. None are enterprise assets or complete commercial CAE input decks. `.hm` is a source modeling asset; the platform executes an exported `.fem`/deck package and does not parse real `.hm` binary files.

| Package | Analysis / condition | Purpose |
|---|---|---|
| `structure/case-good` | STRUCTURE / BIW_TORSION | Normal full workflow |
| `structure/case-001` | STRUCTURE / BIW_TORSION | API reference example |
| `structure/case-baseline` | STRUCTURE / BIW_TORSION | V36 baseline, seed 11, thickness 0.95 |
| `structure/case-current` | STRUCTURE / BIW_TORSION | V37 current, seed 11, thickness 1.05 |
| `structure/case-missing-include` | STRUCTURE / BIW_TORSION | Deliberately missing material include |
| `nvh/case-001` | NVH / CABIN_NVH | PILOT metrics |
| `crash/case-001` | CRASH / FRONTAL_CRASH | PILOT metrics |
| `cfd/case-001` | CFD / DRAG | PILOT metrics |

Each package has a manifest, entry deck, explicit INCLUDEs, parameters, metadata and optional declared SHA-256 checksums. Validation must reject absolute/desktop paths, traversal and symlink escape; verify every referenced include; and match analysis/condition to the Job.

The job root is `DATA_ROOT/jobs/{jobNo}`. Validated input is copied into `input/`; `input-checksums.json` records `{files: {relativePath: sha256}, checksum: aggregateSha256}`. The aggregate is SHA-256 over sorted `relativePath:sha256\n` lines. Files in the snapshot are read-only. Workers verify both individual files and the aggregate against the persisted Job checksum before starting a solver. Execution outputs go into separate attempt directories under `executions/`.

A later change in source `demo-data` must have no effect on an existing snapshot. This file describes the M2 contract; see progress for validation evidence.
