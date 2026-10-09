# Upgrade Guide — PipelineK Studio

This document covers **operator-driven** upgrades of a running
Studio installation. It is intentionally procedural and does NOT
touch the database directly. Database migration is handled by
Flyway; this guide tells you when to expect a migration, how to
verify it, and how to roll back if it goes wrong.

## Versioning

Studio follows [SemVer 2.0](https://semver.org/) with a pre-1.0
spiral: while the project is on `0.x.y` the minor digit is the
"feature milestone" (S0..S5) and the patch digit is the
compatibility level within that milestone. Breaking schema changes
**bump the minor digit** even in `0.x`.

| Version  | Meaning                                                     |
|----------|-------------------------------------------------------------|
| 0.1.0-alpha.1 | Initial S0 bootstrap surface (current)                  |
| 0.2.0-alpha.1 | S1 interactive console (Monaco SPA)                    |
| 0.3.0-alpha.1 | S2 plugin lab                                           |
| 0.4.0-beta.1  | S3 diagnostics + IR Explorer                            |
| 0.5.0-beta.1  | S4 agent collaboration                                  |
| 1.0.0-rc.1    | S5 release candidate                                    |

## Pre-flight checklist

Before any upgrade:

1. **Take a backup** of the database. The supported mechanism is
   the SQLite Online Backup API (no lock, no downtime). From the
   CLI:
   ```bash
   studio backup --db studio.db --dest studio.db.bak.$(date +%s)
   ```
2. **Verify the backup** with the integrity check. Studio refuses
   to start on a corrupted DB:
   ```bash
   sqlite3 studio.db.bak "PRAGMA integrity_check;"
   # expect: ok
   ```
3. **Stop the running server**. Two writers against the same DB
   will fail with HTTP 409 + DB lock.
   ```bash
   # if you have a PID file:
   kill $(cat studio.pid)
   # otherwise: ps + kill by command line
   ```
4. **Note the current version** so you can write the rollback plan:
   ```bash
   studio --version
   ```

## Upgrade steps

1. Replace the distribution tree:
   ```bash
   # extract the new tarball (or `git pull` + `./gradlew :studio-cli:installDist`)
   tar -xzf pipelinek-studio-X.Y.Z.tar.gz -C /opt/studio --strip-components=1
   ```
2. Verify the new distribution's checksums against the manifest:
   ```bash
   cd /opt/studio
   sha256sum -c dist/checksums.txt
   ```
3. Start the server. Flyway will run the migrations on first
   connection:
   ```bash
   studio start --db /var/lib/studio/studio.db
   ```
4. Watch the logs for `Flyway: Successfully applied N migrations`.
   `N == 0` is fine on a same-version upgrade; `N > 0` means a
   schema migration ran.
5. Hit the health endpoint to confirm the server is up:
   ```bash
   studio doctor
   # expect: health: {status=UP, version=...}
   ```

## Database migration policy

Studio uses **Flyway** with versioned SQL migrations under
`studio-infra-storage/src/main/resources/db/migration/V*.sql`.
Each migration is forward-only and never edited once committed.

The migration contract:

- **Additive migrations** (new tables, new columns with defaults,
  new indexes) are SAFE in any release.
- **Destructive migrations** (column drops, type changes, table
  renames) require a minor-version bump and a `R-04` write-up in
  the release notes.
- **No data backfill** runs at migration time. If a backfill is
  needed, the migration is split: a forward DDL + a separate
  `BackgroundDataMigrator` task that the operator can pause and
  resume.

## Rollback

Rollback is **not automatic** — Flyway's `repair` is not exposed
by the CLI on purpose. The supported rollback path is:

1. Stop the new server.
2. Restore the DB from the backup taken in step 1 of the
   pre-flight:
   ```bash
   studio restore --from studio.db.bak.<timestamp> --to studio.db
   ```
3. Replace the distribution tree with the previous version.
4. Start the old server. It must accept the old schema (which it
   will — the DB is now back to the pre-migration state).
5. File an issue with the migration SHA, the observed failure,
   and the original `studio.db` (if it can be shared without
   secrets).

## Hot upgrades

Studio does **not** support zero-downtime upgrades. The
single-writer rule (R-08) is non-negotiable: a long-running
pipeline owns its per-run DB and cannot be migrated mid-flight.
If zero-downtime is required, run two Studio instances in
parallel against separate DBs and switch traffic at the reverse
proxy (see `docs/05-testing/SANDBOX_TLS.md` for the operator-side
config).

## When the upgrade is risky

- The release notes flag `R-04` (version drift) or `R-08`
  (multi-writer) — do the upgrade offline.
- The DB is on a network filesystem (NFS / CIFS) — SQLite does
  not work reliably on those. Move the DB to local disk first.
- The CLI is older than the server — the API is forward-compatible
  but not backward-compatible. Upgrade the CLI alongside the
  server.

## See also

- `TROUBLESHOOTING.md` — symptom-fix entries for migration
  failures, DB lock, and OOM.
- `docs/05-testing/SANDBOX_TLS.md` — reverse-proxy config for
  blue/green upgrades.
- `CHANGELOG.md` — per-version migration notes.
