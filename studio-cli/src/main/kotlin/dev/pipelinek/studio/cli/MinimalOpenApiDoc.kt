package dev.pipelinek.studio.cli

/**
 * S5 E5.3 — the minimal `studio start` server advertises the same OpenAPI 3.1
 * document as the full `studio-app`. We keep a parallel copy here so the CLI
 * stays independent of `studio-http` (the minimal server is a smoke server, not
 * a contract surface) and so the from-clean-clone verification script can check
 * `/openapi.json` without booting the full backend.
 *
 * KEEP IN SYNC with `dev.pipelinek.studio.http.OpenApiDoc` when a route is added
 * or removed.
 */
object MinimalOpenApiDoc {
    val current: String = """
        {
          "openapi": "3.1.0",
          "x-studio-api-version": "1.0.0",
          "info": {
            "title": "PipelineK Studio HTTP API",
            "version": "0.1.0-alpha.1",
            "description": "Control plane for experiments, snapshots, runs, and the read-side ports over the PipelineK SDK. S0..S5 partial."
          },
          "servers": [{ "url": "https://127.0.0.1:7778", "description": "loopback HTTPS; use --insecure-http flag for plain HTTP on :7777" }],
          "paths": {
            "/health": { "get": { "summary": "Liveness probe", "responses": { "200": { "description": "ok" } } } },
            "/api/version": { "get": { "summary": "Studio + PipelineK runtime version", "responses": { "200": { "description": "version document" } } } },
            "/api/metrics": { "get": { "summary": "JVM metrics for cold vs steady-state profiling (S5)", "responses": { "200": { "description": "metrics document" } } } },
            "/api/ready": { "get": { "summary": "Readiness (StudioDatabase open)", "responses": { "200": { "description": "ready" } } } },
            "/openapi.json": { "get": { "summary": "This document", "responses": { "200": { "description": "OpenAPI 3.1 JSON" } } } },
            "/experiments": {
              "post": { "summary": "Create an experiment", "requestBody": { "required": true }, "responses": { "200": { "description": "created" }, "400": { "description": "BAD_REQUEST" } } }
            },
            "/experiments/{experimentId}/snapshots": {
              "post": { "summary": "Seal a Kotlin source snapshot", "responses": { "200": { "description": "sealed" }, "400": { "description": "BAD_REQUEST" } } }
            },
            "/runs": {
              "post": { "summary": "Start a run (idempotent via Idempotency-Key header)", "responses": { "200": { "description": "admitted" }, "202": { "description": "accepted-with-caveat" } } }
            },
            "/runs/{runId}": { "get": { "summary": "Inspect a run (state)", "responses": { "200": { "description": "state" }, "202": { "description": "unknown upstream state" } } } },
            "/runs": {
              "get": { "summary": "List runs (paginated, optional state filter)", "responses": { "200": { "description": "list of run summaries" } } },
              "post": { "summary": "Start a run (idempotent)", "responses": { "200": { "description": "admitted" } } }
            },
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
