-- V5: SFW-H5.5 (2026-10-13) — add the placement_audit column
-- that records which worker hosted each run.
-- Per docs/fabric-coordinated-evolution/coordination/sfw-h5-5-placement-audit-contract.md:
--   - The H5.1 ScheduleRunUseCase returns ScheduleRunResult.Placed(
--     workerId, region) when the worker pool placed a worker.
--     H5.5 ships the persistence: the placed worker is
--     recorded in the run_bindings.placement_audit column.
--   - The column is TEXT (not structured columns) because the
--     H2.3 store is below the hexagonal boundary; storing the
--     JSON keeps the storage layer type-agnostic. The use case
--     layer parses the JSON back to the PlacementAudit data
--     class.
--   - A pre-H5.5 row (created by the local start-run flow or
--     the H2.2c admit path before the V5 migration) keeps
--     working without migration data; a row with
--     placement_audit IS NULL is a pre-H5.5 row.

ALTER TABLE run_bindings ADD COLUMN placement_audit TEXT;
