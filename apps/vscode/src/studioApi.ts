// R5 EVO-C.T3 (2026-10-16) — typed Studio HTTP client for the extension.
//
// Why this module exists: the generated `sdk.ts` wraps only the pre-R5
// routes (`/health`, `/openapi.json`, `POST /experiments`, `POST
// /experiments/{id}/snapshots`, `GET /steps`, `GET|POST /runs`,
// `/runs/{id}`, `/runs/{id}/events`, `/runs/{id}/output`,
// `/runs/{id}/diagnostics`). The tree bootstrap and cancellation need
// `GET /experiments`, `GET /experiments/{id}/snapshots`, `GET /scenarios`
// and `POST /runs/{id}/cancel`, which the generated file does not know about
// (regenerating it is a separate, reviewable change to a generated
// artifact). So:
//   - the generated SDK stays the transport for the routes it covers;
//   - a small typed read/cancel helper covers the rest.
// Both paths go through `fetchWithTimeout` + the profile headers from
// `validateStudioConfig`, so there is exactly one transport policy and one
// validation policy. Nothing here invents data: a payload that does not
// match the contract raises instead of producing an empty tree.

import { StudioClient } from "./sdk";
import { fetchWithTimeout, resolveTimeoutMs, validateStudioUrl, type ValidatedStudioConfig } from "./config";

/** Minimal experiment projection the tree needs (canonical id only). */
export interface ExperimentMeta {
  id: string;
  name: string;
  createdAt: string;
}

/** Snapshot projection. `bytes` is only known from an SSE event. */
export interface SnapshotMeta {
  experimentId: string;
  snapshotId: string;
  sha256?: string;
  createdAt: string;
  bytes?: number;
}

/** Run projection. Association to an experiment uses the canonical id. */
export interface RunMeta {
  experimentId: string;
  runId: string;
  state: string;
  snapshotId?: string;
  scenarioId?: string;
  createdAt: string;
}

/**
 * Authoritative read of the studio's collections. `truncated` is true when
 * the client stopped paging before the server ran out of rows: the client
 * then keeps rows it already knows instead of deleting them, so a partial
 * page can never erase a live experiment.
 */
export interface TreeSnapshot {
  experiments: ExperimentMeta[];
  snapshots: SnapshotMeta[];
  runs: RunMeta[];
  truncated: boolean;
}

export interface PageRequest {
  limit?: number;
  offset?: number;
}

export interface CollectionPage<T> {
  items: T[];
  /** True when the page is full, i.e. another page may exist. */
  full: boolean;
}

export interface JournalEvent {
  sequence: number;
  kind: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface JournalPage {
  events: JournalEvent[];
  nextSequence: number | null;
}

export interface StepMeta {
  id: string;
  name?: string;
}

/**
 * Scenario projection from `GET /scenarios` (R0.3.1). The list route serves
 * an empty `created_at` because the read shape does not carry it, so the
 * field is optional here instead of being invented.
 */
export interface ScenarioMeta {
  scenarioId: string;
  version: number;
  pluginLockDigest?: string;
  environmentProfile: string;
  createdAt?: string;
}

/**
 * Result of `POST /runs/{id}/cancel`. The route answers 200 for both
 * "cancel requested" and "already terminal" (it does not distinguish them
 * on the wire), 202 `CANCEL_UNKNOWN` and 503 `CANCEL_REFUSED`. We report
 * exactly that and never claim the run reached a terminal state.
 */
export interface CancelOutcome {
  kind: "acknowledged" | "unknown" | "refused";
  detail: string;
  status: number;
}

export class StudioApiError extends Error {
  override readonly name = "StudioApiError";
  constructor(
    message: string,
    readonly status: number | null = null,
    readonly code: string | null = null,
  ) {
    super(message);
  }
}

export interface StudioApiOptions {
  timeoutMs?: number;
}

const MAX_PAGE_SIZE = 200;
const DEFAULT_PAGE_SIZE = 200;
const DEFAULT_MAX_PAGES = 5;
/** Total request budget for one authoritative snapshot (all collections). */
const DEFAULT_PAGE_BUDGET = 24;

export class StudioApi {
  readonly config: ValidatedStudioConfig;
  private readonly timeoutMs: number;
  private readonly sdk: StudioClient;

  constructor(config: ValidatedStudioConfig, options: StudioApiOptions = {}) {
    // Defence in depth: the caller is expected to have run
    // `validateStudioConfig`, but a URL that is not HTTP(S) or that
    // smuggles credentials never reaches a socket from here.
    validateStudioUrl(config.baseUrl);
    this.config = config;
    this.timeoutMs = options.timeoutMs ?? resolveTimeoutMs();
    this.sdk = new StudioClient({
      baseUrl: config.baseUrl,
      defaultHeaders: config.headers,
      timeoutMs: this.timeoutMs,
    });
  }

  async health(): Promise<string> {
    const res = await this.send("GET", "/health");
    return this.requireText(res, "health");
  }

  async openapiDocument(): Promise<string> {
    const res = await this.send("GET", "/openapi.json");
    return this.requireText(res, "OpenAPI document");
  }

  async listSteps(): Promise<StepMeta[]> {
    const body = asRecord(this.requireJson(await this.send("GET", "/steps"), "step catalog"), "step catalog");
    const steps = body.steps;
    if (!Array.isArray(steps)) throw new StudioApiError("unexpected step catalog: `steps` is not an array");
    return steps.map((step) => {
      const record = asRecord(step, "step catalog entry");
      return { id: asString(record, "id", "step catalog entry"), name: optionalString(record, "name") };
    });
  }

  async createExperiment(name: string): Promise<{ experimentId: string; name: string }> {
    const raw = await this.sdk.postExperiments({ name });
    const body = asRecord(raw, "create experiment response");
    return {
      experimentId: asString(body, "experiment_id", "create experiment response"),
      name: asString(body, "name", "create experiment response"),
    };
  }

  async sealSnapshot(experimentId: string, source: string, creator: string): Promise<{ snapshotId: string }> {
    const raw = await this.sdk.postExperimentsExperimentIdSnapshots(experimentId, { source, creator });
    const body = asRecord(raw, "seal snapshot response");
    return { snapshotId: asString(body, "snapshot_id", "seal snapshot response") };
  }

  async startRun(
    experimentId: string,
    snapshotId: string,
    scenarioId: string,
    idempotencyKey: string,
  ): Promise<{ runId: string; state: string }> {
    const res = await this.send("POST", "/runs", {
      headers: { "Idempotency-Key": idempotencyKey },
      // `experiment_id` is a required field of the documented
      // `StartRunRequest`; omitting it made the studio answer 400.
      body: { experiment_id: experimentId, snapshot_id: snapshotId, scenario_id: scenarioId },
    });
    const body = asRecord(this.requireJson(res, "start run response"), "start run response");
    return {
      runId: asString(body, "run_id", "start run response"),
      state: asString(body, "state", "start run response"),
    };
  }

  /**
   * Walk every page of the experiment collection. The link/change command
   * needs the complete set: with a single page a real experiment beyond it
   * would be reported as "does not exist". `truncated` says so honestly.
   */
  async experiments(
    options: { pageSize?: number; maxPages?: number } = {},
  ): Promise<{ items: ExperimentMeta[]; truncated: boolean }> {
    const pageSize = clamp(options.pageSize ?? DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
    const maxPages = Math.max(1, options.maxPages ?? DEFAULT_MAX_PAGES);
    const items: ExperimentMeta[] = [];
    const truncated = await this.walk(
      (req) => this.listExperiments(req),
      items,
      pageSize,
      maxPages,
      { left: maxPages },
    );
    return { items, truncated };
  }

  /** One page of the registered scenario records (`GET /scenarios`). */
  async listScenarios(request: PageRequest = {}): Promise<CollectionPage<ScenarioMeta>> {
    const res = await this.send("GET", withPage("/scenarios", request));
    const items = collectionItems(this.requireJson(res, "scenario catalog"), "scenario catalog");
    return {
      items: items.map((item) => {
        const record = asRecord(item, "scenario catalog entry");
        return {
          scenarioId: asString(record, "scenario_id", "scenario catalog entry"),
          version: asInteger(record, "version", "scenario catalog entry"),
          pluginLockDigest: optionalString(record, "plugin_lock_digest"),
          environmentProfile: asString(record, "environment_profile", "scenario catalog entry"),
          createdAt: optionalString(record, "created_at"),
        };
      }),
      full: items.length >= pageLimit(request),
    };
  }

  /**
   * Every registered scenario. A run is only ever submitted with an id that
   * came from here; the extension never invents one.
   */
  async scenarios(
    options: { pageSize?: number; maxPages?: number } = {},
  ): Promise<{ items: ScenarioMeta[]; truncated: boolean }> {
    const pageSize = clamp(options.pageSize ?? DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
    const maxPages = Math.max(1, options.maxPages ?? DEFAULT_MAX_PAGES);
    const items: ScenarioMeta[] = [];
    const truncated = await this.walk(
      (req) => this.listScenarios(req),
      items,
      pageSize,
      maxPages,
      { left: maxPages },
    );
    return { items, truncated };
  }

  async listExperiments(request: PageRequest = {}): Promise<CollectionPage<ExperimentMeta>> {
    const res = await this.send("GET", withPage("/experiments", request));
    const items = collectionItems(this.requireJson(res, "experiment collection"), "experiment collection");
    return {
      items: items.map((item) => {
        const record = asRecord(item, "experiment collection entry");
        return {
          id: asString(record, "experiment_id", "experiment collection entry"),
          name: asString(record, "name", "experiment collection entry"),
          createdAt: asString(record, "created_at", "experiment collection entry"),
        };
      }),
      full: items.length >= pageLimit(request),
    };
  }

  async listExperimentSnapshots(experimentId: string, request: PageRequest = {}): Promise<CollectionPage<SnapshotMeta>> {
    const res = await this.send(
      "GET",
      withPage(`/experiments/${encodeURIComponent(experimentId)}/snapshots`, request),
    );
    const items = collectionItems(this.requireJson(res, "snapshot collection"), "snapshot collection");
    return {
      items: items.map((item) => {
        const record = asRecord(item, "snapshot collection entry");
        return {
          experimentId: asString(record, "experiment_id", "snapshot collection entry"),
          snapshotId: asString(record, "snapshot_id", "snapshot collection entry"),
          sha256: optionalString(record, "sha256"),
          createdAt: asString(record, "created_at", "snapshot collection entry"),
        };
      }),
      full: items.length >= pageLimit(request),
    };
  }

  async listRuns(request: PageRequest = {}): Promise<CollectionPage<RunMeta>> {
    const res = await this.send("GET", withPage("/runs", request));
    const items = collectionItems(this.requireJson(res, "run collection"), "run collection");
    return {
      items: items.map((item) => {
        const record = asRecord(item, "run collection entry");
        return {
          experimentId: asString(record, "experiment_id", "run collection entry"),
          runId: asString(record, "run_id", "run collection entry"),
          state: asString(record, "state", "run collection entry"),
          snapshotId: optionalString(record, "snapshot_id"),
          scenarioId: optionalString(record, "scenario_id"),
          createdAt: asString(record, "created_at", "run collection entry"),
        };
      }),
      full: items.length >= pageLimit(request),
    };
  }

  async runState(runId: string): Promise<{ runId: string; state: string }> {
    const res = await this.send("GET", `/runs/${encodeURIComponent(runId)}`);
    const body = asRecord(this.requireJson(res, "run status"), "run status");
    return {
      runId: asString(body, "run_id", "run status"),
      state: asString(body, "state", "run status"),
    };
  }

  async cancelRun(runId: string): Promise<CancelOutcome> {
    const res = await this.send("POST", `/runs/${encodeURIComponent(runId)}/cancel`);
    const body = optionalJson(res.text);
    const code = body && typeof body.code === "string" ? body.code : null;
    const message = body && typeof body.message === "string" ? body.message : `HTTP ${res.status}`;
    if (res.status === 200) return { kind: "acknowledged", detail: "acknowledged", status: 200 };
    if (res.status === 202 && code === "CANCEL_UNKNOWN") return { kind: "unknown", detail: message, status: res.status };
    if (res.status === 503 && code === "CANCEL_REFUSED") return { kind: "refused", detail: message, status: res.status };
    throw new StudioApiError(`${code ?? `HTTP ${res.status}`}: ${message}`, res.status, code);
  }

  async diagnostics(runId: string): Promise<unknown> {
    return this.requireJson(await this.send("GET", `/runs/${encodeURIComponent(runId)}/diagnostics`), "diagnostics");
  }

  async output(runId: string): Promise<unknown> {
    return this.requireJson(await this.send("GET", `/runs/${encodeURIComponent(runId)}/output`), "run output");
  }

  /** One page of the run's event journal (the same contract the SPA uses). */
  async journalPage(runId: string, after: number | null, limit: number): Promise<JournalPage> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (after !== null) query.set("after", String(after));
    const res = await this.send("GET", `/runs/${encodeURIComponent(runId)}/events?${query.toString()}`);
    const body = asRecord(this.requireJson(res, "run journal"), "run journal");
    const events = body.events;
    if (!Array.isArray(events)) {
      // 202 EVENTS_UNKNOWN is a 2xx refusal: report the server's code
      // instead of guessing what the journal "probably" contained.
      const code = typeof body.code === "string" ? body.code : null;
      throw new StudioApiError(
        `run journal unavailable: ${code ?? "unexpected payload (no `events` array)"}`,
        res.status,
        code,
      );
    }
    return {
      events: events.map((event) => {
        const record = asRecord(event, "run journal event");
        const sequence = record.sequence;
        if (typeof sequence !== "number" || !Number.isInteger(sequence) || sequence < 0) {
          throw new StudioApiError("unexpected run journal event: `sequence` is not a non-negative integer");
        }
        const payload = record.payload;
        return {
          sequence,
          kind: asString(record, "kind", "run journal event"),
          occurredAt: asString(record, "occurredAt", "run journal event"),
          payload: payload && typeof payload === "object" && !Array.isArray(payload)
            ? (payload as Record<string, unknown>)
            : {},
        };
      }),
      nextSequence: typeof body.nextSequence === "number" ? body.nextSequence : null,
    };
  }

  /** Walk the journal by cursor, bounded by `maxPages`. */
  async journal(runId: string, options: { pageSize?: number; maxPages?: number } = {}): Promise<JournalEvent[]> {
    const pageSize = clamp(options.pageSize ?? 200, 1, 500);
    const maxPages = Math.max(1, options.maxPages ?? DEFAULT_MAX_PAGES);
    const events: JournalEvent[] = [];
    let after: number | null = null;
    for (let page = 0; page < maxPages; page += 1) {
      const result = await this.journalPage(runId, after, pageSize);
      events.push(...result.events);
      if (result.nextSequence === null || result.events.length === 0) return events;
      // A cursor that does not advance would loop forever on a
      // misbehaving server; stop and return what was really read.
      if (after !== null && result.nextSequence <= after) return events;
      after = result.nextSequence;
    }
    return events;
  }

  /**
   * Read every collection the tree needs. Bounded: `pageSize` per request,
   * `maxPages` per collection and a shared request budget, so an
   * unexpectedly large studio cannot turn a reconnect into a stampede.
   */
  async collectTree(
    options: { pageSize?: number; maxPages?: number; pageBudget?: number } = {},
  ): Promise<TreeSnapshot> {
    const pageSize = clamp(options.pageSize ?? DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
    const maxPages = Math.max(1, options.maxPages ?? DEFAULT_MAX_PAGES);
    const budget = { left: Math.max(1, options.pageBudget ?? DEFAULT_PAGE_BUDGET) };

    const experiments: ExperimentMeta[] = [];
    const snapshots: SnapshotMeta[] = [];
    const runs: RunMeta[] = [];

    let truncated = await this.walk((req) => this.listExperiments(req), experiments, pageSize, maxPages, budget);
    for (const experiment of experiments) {
      if (budget.left <= 0) {
        truncated = true;
        break;
      }
      const collectionTruncated = await this.walk(
        (req) => this.listExperimentSnapshots(experiment.id, req),
        snapshots,
        pageSize,
        maxPages,
        budget,
      );
      truncated = truncated || collectionTruncated;
    }
    truncated = (await this.walk((req) => this.listRuns(req), runs, pageSize, maxPages, budget)) || truncated;

    return { experiments, snapshots, runs, truncated };
  }

  private async walk<T>(
    fetchPage: (request: PageRequest) => Promise<CollectionPage<T>>,
    into: T[],
    pageSize: number,
    maxPages: number,
    budget: { left: number },
  ): Promise<boolean> {
    for (let page = 0; page < maxPages; page += 1) {
      if (budget.left <= 0) return true;
      budget.left -= 1;
      const result = await fetchPage({ limit: pageSize, offset: page * pageSize });
      into.push(...result.items);
      if (!result.full) return false;
    }
    return true;
  }

  private url(path: string): string {
    return `${this.config.baseUrl}${path}`;
  }

  private async send(
    method: string,
    path: string,
    options: { body?: unknown; headers?: Record<string, string> } = {},
  ): Promise<{ status: number; text: string }> {
    const headers: Record<string, string> = { ...this.config.headers, ...(options.headers ?? {}) };
    let body: string | undefined;
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(options.body);
    }
    const response = await fetchWithTimeout(
      this.url(path),
      // `redirect: "error"`: a redirect is never followed, so an HTTPS
      // endpoint cannot be bounced to plaintext by the server.
      { method, headers, body, redirect: "error" },
      this.timeoutMs,
    );
    return { status: response.status, text: await response.text() };
  }

  private requireText(res: { status: number; text: string }, what: string): string {
    if (res.status < 200 || res.status >= 300) throw this.serverError(res, what);
    return res.text;
  }

  private requireJson(res: { status: number; text: string }, what: string): unknown {
    if (res.status < 200 || res.status >= 300) throw this.serverError(res, what);
    const parsed = optionalJson(res.text);
    if (parsed === null) throw new StudioApiError(`unexpected ${what}: HTTP ${res.status} body was not JSON`, res.status);
    return parsed;
  }

  private serverError(res: { status: number; text: string }, what: string): StudioApiError {
    const body = optionalJson(res.text);
    const code = body && typeof body.code === "string" ? body.code : null;
    const message = body && typeof body.message === "string" ? body.message : `HTTP ${res.status}`;
    return new StudioApiError(`${what} failed: ${code ?? `HTTP ${res.status}`}: ${message}`, res.status, code);
  }
}

function withPage(path: string, request: PageRequest): string {
  const query = new URLSearchParams({
    limit: String(request.limit ?? DEFAULT_PAGE_SIZE),
    offset: String(request.offset ?? 0),
  });
  return `${path}?${query.toString()}`;
}

function pageLimit(request: PageRequest): number {
  return request.limit ?? DEFAULT_PAGE_SIZE;
}

function collectionItems(body: unknown, what: string): unknown[] {
  const record = asRecord(body, what);
  if (!Array.isArray(record.items)) throw new StudioApiError(`unexpected ${what}: \`items\` is not an array`);
  return record.items;
}

function optionalJson(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
    return null;
  } catch {
    return null;
  }
}

function asRecord(value: unknown, what: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new StudioApiError(`unexpected ${what}: expected a JSON object`);
  }
  return value as Record<string, unknown>;
}

function asString(record: Record<string, unknown>, key: string, what: string): string {
  const value = record[key];
  if (typeof value !== "string" || value === "") {
    throw new StudioApiError(`unexpected ${what}: \`${key}\` is missing or not a non-empty string`);
  }
  return value;
}

function optionalString(record: Record<string, unknown>, key: string): string | undefined {
  const value = record[key];
  return typeof value === "string" && value !== "" ? value : undefined;
}

function asInteger(record: Record<string, unknown>, key: string, what: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    throw new StudioApiError(`unexpected ${what}: \`${key}\` is not a non-negative integer`);
  }
  return value;
}

/**
 * Deterministic `Idempotency-Key` for a submit of one sealed snapshot under
 * one scenario. The studio rejects keys that are not `[A-Za-z0-9_-]+` or that
 * exceed 128 characters (audit H-17), so the key is sanitised and capped here
 * rather than sent and refused.
 */
export function runIdempotencyKey(snapshotId: string, scenarioId: string): string {
  return `vscode-${snapshotId}-${scenarioId}`.replace(/[^A-Za-z0-9_-]/g, "-").slice(0, 128);
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.trunc(value)));
}
