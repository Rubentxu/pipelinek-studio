-- V7: Studio P0-03 — durable Fabric start intent (command identity).
-- Per docs/02-specifications/FABRIC_INTEGRATION_SPEC.md §3:
--   - the full request, the StartRunId (Fabric `commandId`) and its
--     identity digest are stored BEFORE calling StartRun, so a retry
--     after a crash preserves exactly the same request;
--   - the row is keyed by the Studio run, so one Studio run has at most
--     one command identity, while two distinct Studio runs get distinct
--     identities even for identical content;
--   - the FK to run_bindings means an identity can only exist for a
--     run the local start-run flow already created (no identity, no
--     dispatch);
--   - the fingerprint column is NOT NULL: a reservation without content
--     identity cannot be stored.
--
-- The migration is additive: existing rows/tables are untouched and a
-- pre-V7 studio.db keeps working (the table simply starts empty).

CREATE TABLE IF NOT EXISTS fabric_start_intents (
    studio_run_id TEXT PRIMARY KEY REFERENCES run_bindings(studio_run_id),
    command_id TEXT NOT NULL UNIQUE,
    -- 71 = len('sha256:') + 64 lowercase hex chars. The CHECK keeps the
    -- column a validated content digest even for a writer that bypasses
    -- the application port.
    request_fingerprint TEXT NOT NULL CHECK (
        length(request_fingerprint) = 71
        AND substr(request_fingerprint, 1, 7) = 'sha256:'
    ),
    created_at TEXT NOT NULL
);
