-- V1: initial Studio metadata schema.
-- Per docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md:
--   - 'studio.db' is for Studio metadata only; PipelineK run.db lives separately.
--   - prefix studio_schema_version; migrations idempotent; backup/restore tested separately.
--   - one run.db per run (decided at S0 feature document, see DECISION D-008 placeholder).

CREATE TABLE IF NOT EXISTS studio_schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS experiments (
    experiment_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id TEXT PRIMARY KEY,
    experiment_id TEXT NOT NULL REFERENCES experiments(experiment_id),
    sha256 TEXT NOT NULL,
    source_utf8 BLOB NOT NULL,
    creator TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(experiment_id, sha256)
);

CREATE TABLE IF NOT EXISTS scenarios (
    scenario_id TEXT PRIMARY KEY,
    version INTEGER NOT NULL,
    environment_profile TEXT NOT NULL,
    plugin_lock_digest TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS run_bindings (
    studio_run_id TEXT PRIMARY KEY,
    experiment_id TEXT NOT NULL REFERENCES experiments(experiment_id),
    snapshot_id TEXT NOT NULL REFERENCES snapshots(snapshot_id),
    scenario_id TEXT NOT NULL REFERENCES scenarios(scenario_id),
    upstream_run_id TEXT,
    run_db_ref TEXT,
    control_root_ref TEXT,
    runtime_version TEXT NOT NULL,
    runtime_digest TEXT NOT NULL,
    plugin_set_digest TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (state IN (
        'CREATED','ADMITTED','QUEUED','STARTING','RUNNING',
        'SUCCEEDED','FAILED','CANCELLED','REFUSED',
        'RECOVERY_REQUIRED','LOST','UNKNOWN'
    ))
);

CREATE INDEX IF NOT EXISTS ix_run_bindings_state ON run_bindings(state);
CREATE INDEX IF NOT EXISTS ix_run_bindings_experiment ON run_bindings(experiment_id);

CREATE TABLE IF NOT EXISTS idempotency (
    scope_id TEXT NOT NULL,
    operation_name TEXT NOT NULL,
    key TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    operation_id TEXT,
    response_ref TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY(scope_id, operation_name, key)
);

CREATE TABLE IF NOT EXISTS run_state_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    studio_run_id TEXT NOT NULL REFERENCES run_bindings(studio_run_id),
    from_state TEXT,
    to_state TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    reason TEXT
);

CREATE TABLE IF NOT EXISTS runtime_descriptor (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version TEXT NOT NULL,
    artifact_digest TEXT NOT NULL,
    plugin_sdk_version TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);
