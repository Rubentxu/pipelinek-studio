-- V6: SFW-H6.1 (2026-10-13) — add the dedup_key column
-- for content-based deduplication.
-- Per docs/fabric-coordinated-evolution/coordination/sfw-h6-1-dedup-key-port-contract.md:
--   - The H6.1 DedupRunUseCase computes a SHA-256 of the
--     canonical RunRequest shape (snapshotId + scenarioId +
--     profile joined with NUL) and asks the DedupKeyPort
--     whether a run with the same key already exists.
--   - The column is TEXT (not a hash type) because SQLite
--     does not have a native SHA-256 type; storing the hex-
--     encoded hash keeps the storage layer type-agnostic.
--   - A pre-H6.1 row (dedupKey = null) is a valid state;
--     the V6 migration adds the column as NULL by default;
--     a legacy row keeps working without migration data.
--   - The CREATE INDEX makes the dedup-key lookup hot;
--     the O(1) index lookup is critical for the dedup-
--     equivalence UAT (the same content must not re-submit).

ALTER TABLE run_bindings ADD COLUMN dedup_key TEXT;
CREATE INDEX idx_run_bindings_dedup_key ON run_bindings(dedup_key);
