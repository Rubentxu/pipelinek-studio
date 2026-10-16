-- V3: SFW-H2.3 (2026-10-13) — add the 3 columns the H2 fabric binding needs.
-- Per docs/fabric-coordinated-evolution/coordination/sfw-h2-3-studio-run-bindings-contract.md:
--   - 'run_bindings' is one row at different stages of its life. V1
--     creates the row with the local SDK binding (upstream_run_id,
--     run_db_ref, control_root_ref). The H2.3 columns are populated
--     later, when (a) the run is admitted to a Fabric server and
--     (b) a fact stream surfaces the executor's id.
--   - All three new columns are NULL by default. A pre-H2.3 row
--     (created by the local start-run flow) keeps working without
--     migration data; a row with fabric_run_id IS NULL is a
--     local-only run.

ALTER TABLE run_bindings ADD COLUMN fabric_run_id TEXT;
ALTER TABLE run_bindings ADD COLUMN pipelinek_run_id TEXT;
ALTER TABLE run_bindings ADD COLUMN package_digest TEXT;
