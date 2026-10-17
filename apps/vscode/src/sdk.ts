// Auto-generated from /openapi.json — DO NOT EDIT BY HAND.
// Regenerate with: studio-cli generate-sdk-ts > sdk.ts
//
// PipelineK Studio TS SDK — typed fetch wrappers.
//
// Audit F-P1-VSCODE-2 (2026-10-10): every request now goes through
// `fetchWithTimeout` so a hung backend surfaces as a clear error
// within the deadline (default 5s) instead of hanging the editor
// forever. The caller can pass `fetchImpl` to override for tests.
//

import { fetchWithTimeout, DEFAULT_TIMEOUT_MS } from "./config";

export interface StudioClientOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
  defaultHeaders?: Record<string, string>;
  /**
   * Per-request timeout in milliseconds. Defaults to
   * [DEFAULT_TIMEOUT_MS] (5s) which matches the MCP and web
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

  /** JVM metrics for cold vs steady-state profiling (S5) */
  async getApiMetrics(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/metrics`);
  }

  /** Studio + PipelineK runtime version */
  async getApiVersion(): Promise<unknown> {
    return this.request<unknown>("GET", `/api/version`);
  }

  /** Create an experiment */
  async postExperiments(body?: unknown): Promise<unknown> {
    return this.request<unknown>("POST", `/experiments`, body);
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

  /** Output Plane (bytes) */
  async getRunsRunIdOutput(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/output`);
  }

  /** SSE push of run events */
  async getRunsRunIdStream(runId: string): Promise<unknown> {
    return this.request<unknown>("GET", `/runs/${runId}/stream`);
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

export const VERSION = "0.1.0-alpha.1";
