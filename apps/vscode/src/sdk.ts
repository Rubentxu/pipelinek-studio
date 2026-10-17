// Auto-generated from /openapi.json — DO NOT EDIT BY HAND.
// Regenerate with: studio-cli generate-sdk-ts > sdk.ts
//
// PipelineK Studio TS SDK — typed fetch wrappers.
// x-studio-api-version: 1.0.0
//
// Audit F-P1-VSCODE-2 (2026-10-10): every request now goes
// through `fetchWithTimeout` so a hung backend surfaces as a
// clear error within the deadline (default 5s) instead of
// hanging the editor forever. The caller can pass `fetchImpl`
// to override for tests.

import { fetchWithTimeout, DEFAULT_TIMEOUT_MS } from "./config";

export const API_VERSION = "1.0.0";

export interface StudioClientOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
  defaultHeaders?: Record<string, string>;
  /**
   * Per-request timeout in milliseconds. Defaults to
   * DEFAULT_TIMEOUT_MS (5s) which matches the MCP and web
   * defaults. A healthy local backend answers in well under 1s.
   */
  timeoutMs?: number;
}

export class StudioClient {
  constructor(private readonly opts: StudioClientOptions) {}

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = {
      'content-type': 'application/json',
      ...(this.opts.defaultHeaders ?? {}),
    };
    const res = await fetchWithTimeout(
      this.opts.baseUrl + path,
      {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
      },
      this.opts.timeoutMs ?? DEFAULT_TIMEOUT_MS,
    );
    if (!res.ok && res.status !== 202) {
      const text = await res.text().catch(() => '');
      throw new Error(`HTTP ${res.status}: ${text}`);
    }
    return res.json() as Promise<T>;
  }

  /** Discover the Studio surface */
  async getRoot(): Promise<unknown> {
    return this.request<unknown>("GET", `/`);
  }

  /** AAT-SEC-010 — issue a workspace lease for a destructive op */
  async postApiAdminLease(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/api/admin/lease`, body);
  }

  /** SFW-H4.6 — server-side restart reconciliation (in-flight run bindings after restart) */
  async postApiAdminRecovery(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/api/admin/recovery`, body);
  }

  /** AAT-DUR-006 — run retention sweep */
  async postApiAdminRetention(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/api/admin/retention`, body);
  }

  /** JVM metrics for cold vs steady-state profiling (S5) */
  async getApiMetrics(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/metrics`);
  }

  /** S5 — server-side restart reconciliation report (in-flight runs after restart) */
  async getApiRecovery(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/recovery`);
  }

  /** SFW-H5.4 — schedule a run against the capacity port (placement_audit) */
  async postApiRunsSchedule(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/api/runs/schedule`, body);
  }

  /** B3.3 — whether static analysis is available (Detekt etc.) and the toolchain provenance */
  async getApiStaticAnalyzeCapability(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/static-analyze-capability`);
  }

  /** Studio + PipelineK runtime version (B5.4 cert surface) */
  async getApiVersion(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/version`);
  }

  /** R5 EVO-C.0 — SSE stream of tree events (experiments/runs/snapshots) */
  async getEvents(): Promise<unknown> {
    return this.request<unknown>("GET", `/events`);
  }

  /** List experiments (collection read for the VS Code tree; newest first, bounded limit/offset) */
  async getExperiments(): Promise<unknown> {
    return this.request<unknown>("GET", `/experiments`);
  }

  /** Create an experiment */
  async postExperiments(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/experiments`, body);
  }

  /** List one experiment's snapshots (newest first, bounded limit/offset) */
  async getExperimentsExperimentIdSnapshots(experimentId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/experiments/${experimentId}/snapshots`);
  }

  /** Seal a Kotlin source snapshot */
  async postExperimentsExperimentIdSnapshots(experimentId: string, body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/experiments/${experimentId}/snapshots`, body);
  }

  /** Liveness probe */
  async getHealth(): Promise<unknown> {
    return this.request<unknown>("GET", `/health`);
  }

  /** This document */
  async getOpenapiJson(): Promise<unknown> {
    return this.request<unknown>("GET", `/openapi.json`);
  }

  /** List runs (S1 read-side, paginated, optional state filter) */
  async getRuns(): Promise<unknown> {
    return this.request<unknown>("GET", `/runs`);
  }

  /** Start a run (idempotent via Idempotency-Key header) */
  async postRuns(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/runs`, body);
  }

  /** Inspect a run (state) */
  async getRunsRunId(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}`);
  }

  /** Request cancellation (does not imply terminal CANCELLED) */
  async postRunsRunIdCancel(runId: string, body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/runs/${runId}/cancel`, body);
  }

  /** Normalized diagnostics for the run */
  async getRunsRunIdDiagnostics(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/diagnostics`);
  }

  /** Event Plane (paginated) */
  async getRunsRunIdEvents(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/events`);
  }

  /** IR Explorer (COMPILED/ADMITTED/OBSERVED phases) */
  async getRunsRunIdIr(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/ir`);
  }

  /** DEPRECATED — legacy per-operation output path, never wired. Use /runs/{runId}/output instead. */
  async getRunsRunIdOperationsOperationIdOutput(runId: string, operationId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/operations/${operationId}/output`);
  }

  /** Output Plane (bytes) */
  async getRunsRunIdOutput(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/output`);
  }

  /** SSE push of run events (R5 EVO-C.0) */
  async getRunsRunIdStream(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/stream`);
  }

  /** List scenarios (collection read) */
  async getScenarios(): Promise<unknown> {
    return this.request<unknown>("GET", `/scenarios`);
  }

  /** R0.3.1 — register a Scenario (canonical scenario id source) */
  async postScenarios(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/scenarios`, body);
  }

  /** Get one sealed snapshot */
  async getSnapshotsSnapshotId(snapshotId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/snapshots/${snapshotId}`);
  }

  /** Admitted Step plugin catalog (S2) */
  async getSteps(): Promise<unknown> {
    return this.request<unknown>("GET", `/steps`);
  }

  /** Describe one step */
  async getStepsStepId(stepId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/steps/${stepId}`);
  }

}

export const VERSION = "1.0.0";
