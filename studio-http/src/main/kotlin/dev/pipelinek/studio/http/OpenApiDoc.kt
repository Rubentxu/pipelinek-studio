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
            "version": "0.1.0-alpha.1",
            "description": "Control plane for experiments, snapshots, runs, and the read-side ports over the PipelineK SDK. S0..S5 partial."
          },
          "servers": [{ "url": "http://127.0.0.1:7777" }],
          "paths": {
            "/health": { "get": { "summary": "Liveness probe", "responses": { "200": { "description": "ok" } } } },
            "/api/version": { "get": { "summary": "Studio + PipelineK runtime version", "responses": { "200": { "description": "version document" } } } },
            "/api/metrics": { "get": { "summary": "JVM metrics for cold vs steady-state profiling (S5)", "responses": { "200": { "description": "metrics document" } } } },
            "/api/static-analyze-capability": { "get": { "summary": "B3.3 — whether static analysis is available (Detekt etc.) and the toolchain provenance", "responses": { "200": { "description": "capability document with `available` flag + `reason` or `analyzer_version`" } } } },
            "/openapi.json": { "get": { "summary": "This document", "responses": { "200": { "description": "OpenAPI 3.1 JSON" } } } },
            "/experiments": {
              "post": { "summary": "Create an experiment", "requestBody": { "required": true }, "responses": { "200": { "description": "created" }, "400": { "description": "BAD_REQUEST" } } }
            },
            "/experiments/{experimentId}/snapshots": {
              "post": { "summary": "Seal a Kotlin source snapshot", "responses": { "200": { "description": "sealed" }, "400": { "description": "BAD_REQUEST" } } }
            },
            "/runs": {
              "post": { "summary": "Start a run (idempotent via Idempotency-Key header)", "responses": { "200": { "description": "admitted" }, "202": { "description": "accepted-with-caveat" } } },
              "get": { "summary": "List runs (S1 read-side, paginated, optional state filter)", "responses": { "200": { "description": "list of run summaries" } } }
            },
            "/runs/{runId}": { "get": { "summary": "Inspect a run (state)", "responses": { "200": { "description": "state" }, "202": { "description": "unknown upstream state" } } } },
            "/runs/{runId}/events": { "get": { "summary": "Event Plane (paginated)", "responses": { "200": { "description": "events" } } } },
            "/runs/{runId}/stream": { "get": { "summary": "SSE push of run events", "responses": { "200": { "description": "text/event-stream" } } } },
            "/runs/{runId}/output": { "get": { "summary": "Output Plane (bytes)", "responses": { "200": { "description": "bytes" }, "503": { "description": "OUTPUT_REFUSED — Output Plane not yet certified" } } } },
            "/runs/{runId}/diagnostics": { "get": { "summary": "Normalized diagnostics for the run", "responses": { "200": { "description": "diagnostics" }, "503": { "description": "DIAGNOSTICS_UNAVAILABLE" } } } },
            "/runs/{runId}/ir": { "get": { "summary": "IR Explorer (COMPILED/ADMITTED/OBSERVED phases)", "responses": { "200": { "description": "ir" }, "503": { "description": "IR_UNAVAILABLE" } } } },
            "/steps": { "get": { "summary": "Admitted Step plugin catalog (S2)", "responses": { "200": { "description": "catalog" } } } },
            "/steps/{stepId}": { "get": { "summary": "Describe one step", "responses": { "200": { "description": "step" }, "404": { "description": "STEP_NOT_FOUND" } } } }
          }
        }
    """.trimIndent()
}
