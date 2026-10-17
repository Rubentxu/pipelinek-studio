package dev.pipelinek.studio.http

/**
 * S5 E5.3 — Curated OpenAPI 3.1 document for the Studio HTTP surface. Served at
 * `GET /openapi.json` so SDK generators and the VS Code client can consume it
 * without scraping the ktor module.
 *
 * The doc is hand-maintained because Ktor's reflection-based extraction would be
 * heavier than the spec we actually need and would expose internal types we do
 * not want in a published contract. Routes added in a future S0..S5 increment
 * must be added here too — `/openapi.json` is the contract.
 *
 * R0.6 sync (2026-10-17): this document and `contracts/openapi.yaml` MUST share
 * the same path set. `OpenApiDocPathParityTest` enforces parity on every build
 * so future drift is caught before it ships. Routes from sibling route files
 * (`PluginAdmissionRoutes`, `ScheduleRunRoutes`, `RecoveryRoutes`,
 * `LiveEventRoutes`, `TreeEventRoutes`) are intentionally out of scope here —
 * those route families ship in follow-up commits with their own bounded
 * contracts.
 *
 * Note on U0..U5 upstream gaps: the doc lists the *exposed* surface; if the
 * underlying port refuses (e.g. SDK output not certified), the response carries
 * the documented refusal code, not a fake success.
 */
object OpenApiDoc {
    val current: String = """
        {
          "openapi": "3.1.0",
          "x-studio-api-version": "1.0.0",
          "info": {
            "title": "PipelineK Studio HTTP API",
            "version": "1.0.0",
            "description": "Control plane for experiments, snapshots, runs, and the read-side ports over the PipelineK SDK. S0..S5 partial."
          },
          "servers": [{ "url": "http://127.0.0.1:7777" }],
          "paths": {
            "/": { "get": { "summary": "Discover the Studio surface", "responses": { "200": { "description": "root response" } } } },
            "/health": { "get": { "summary": "Liveness probe", "responses": { "200": { "description": "ok" } } } },
            "/api/version": { "get": { "summary": "Studio + PipelineK runtime version (B5.4 cert surface)", "responses": { "200": { "description": "version document" } } } },
            "/api/metrics": { "get": { "summary": "JVM metrics for cold vs steady-state profiling (S5)", "responses": { "200": { "description": "metrics document" } } } },
            "/api/static-analyze-capability": { "get": { "summary": "B3.3 — whether static analysis is available (Detekt etc.) and the toolchain provenance", "responses": { "200": { "description": "capability document with `available` flag + `reason` or `analyzer_version`" } } } },
            "/api/recovery": { "get": { "summary": "S5 — server-side restart reconciliation report (in-flight runs after restart)", "responses": { "200": { "description": "recovery report" } } } },
            "/api/admin/lease": { "post": { "summary": "AAT-SEC-010 — issue a workspace lease for a destructive op", "responses": { "201": { "description": "lease issued" }, "400": { "description": "BAD_REQUEST" } } } },
            "/api/admin/retention": { "post": { "summary": "AAT-DUR-006 — run retention sweep", "responses": { "200": { "description": "retention report" }, "400": { "description": "BAD_REQUEST" } } } },
            "/openapi.json": { "get": { "summary": "This document", "responses": { "200": { "description": "OpenAPI 3.1 JSON" } } } },
            "/scenarios": {
              "post": { "summary": "R0.3.1 — register a Scenario (canonical scenario id source)", "responses": { "201": { "description": "scenario registered" }, "400": { "description": "BAD_REQUEST" }, "409": { "description": "VERSION_CONFLICT" } } },
              "get": { "summary": "List scenarios (collection read)", "responses": { "200": { "description": "scenario collection" } } }
            },
            "/experiments": {
              "post": { "summary": "Create an experiment", "requestBody": { "required": true }, "responses": { "200": { "description": "created" }, "400": { "description": "BAD_REQUEST" } } },
              "get": { "summary": "List experiments (collection read for the VS Code tree; newest first, bounded limit/offset)", "parameters": [ { "name": "limit", "in": "query", "schema": { "type": "integer", "minimum": 1, "maximum": 200, "default": 100 } }, { "name": "offset", "in": "query", "schema": { "type": "integer", "minimum": 0, "default": 0 } } ], "responses": { "200": { "description": "experiment collection" }, "400": { "description": "BAD_REQUEST" }, "503": { "description": "COLLECTION_UNAVAILABLE — metadata port does not support collection reads" } } }
            },
            "/experiments/{experimentId}/snapshots": {
              "post": { "summary": "Seal a Kotlin source snapshot", "responses": { "200": { "description": "sealed" }, "400": { "description": "BAD_REQUEST" } } },
              "get": { "summary": "List one experiment's snapshots (newest first, bounded limit/offset)", "parameters": [ { "name": "experimentId", "in": "path", "required": true, "schema": { "type": "string", "format": "uuid" } }, { "name": "limit", "in": "query", "schema": { "type": "integer", "minimum": 1, "maximum": 200, "default": 100 } }, { "name": "offset", "in": "query", "schema": { "type": "integer", "minimum": 0, "default": 0 } } ], "responses": { "200": { "description": "snapshot collection" }, "400": { "description": "BAD_REQUEST" }, "503": { "description": "COLLECTION_UNAVAILABLE" } } }
            },
            "/snapshots/{snapshotId}": { "get": { "summary": "Get one sealed snapshot", "responses": { "200": { "description": "snapshot" }, "404": { "description": "SNAPSHOT_NOT_FOUND" } } } },
            "/runs": {
              "post": { "summary": "Start a run (idempotent via Idempotency-Key header)", "responses": { "200": { "description": "admitted" }, "202": { "description": "accepted-with-caveat" } } },
              "get": { "summary": "List runs (S1 read-side, paginated, optional state filter)", "responses": { "200": { "description": "list of run summaries" } } }
            },
            "/runs/{runId}": { "get": { "summary": "Inspect a run (state)", "responses": { "200": { "description": "state" }, "202": { "description": "unknown upstream state" } } } },
            "/runs/{runId}/cancel": { "post": { "summary": "Request cancellation (does not imply terminal CANCELLED)", "responses": { "202": { "description": "cancellation requested" }, "409": { "description": "RUN_TERMINAL — cannot cancel a finished run" } } } },
            "/runs/{runId}/events": { "get": { "summary": "Event Plane (paginated)", "responses": { "200": { "description": "events" } } } },
            "/runs/{runId}/stream": { "get": { "summary": "SSE push of run events (R5 EVO-C.0)", "responses": { "200": { "description": "text/event-stream" }, "501": { "description": "NOT_WIRED — no bus bound" } } } },
            "/runs/{runId}/output": { "get": { "summary": "Output Plane (bytes)", "responses": { "200": { "description": "bytes" }, "503": { "description": "OUTPUT_REFUSED — Output Plane not yet certified" } } } },
            "/runs/{runId}/diagnostics": { "get": { "summary": "Normalized diagnostics for the run", "responses": { "200": { "description": "diagnostics" }, "503": { "description": "DIAGNOSTICS_UNAVAILABLE" } } } },
            "/runs/{runId}/ir": { "get": { "summary": "IR Explorer (COMPILED/ADMITTED/OBSERVED phases)", "responses": { "200": { "description": "ir" }, "503": { "description": "IR_UNAVAILABLE" } } } },
            "/steps": { "get": { "summary": "Admitted Step plugin catalog (S2)", "responses": { "200": { "description": "catalog" } } } },
            "/steps/{stepId}": { "get": { "summary": "Describe one step", "responses": { "200": { "description": "step" }, "404": { "description": "STEP_NOT_FOUND" } } } }
          }
        }
    """.trimIndent()
}
