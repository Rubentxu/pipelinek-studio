// R5 EVO-C.T3 (2026-10-16) — connection + consent orchestration.
//
// One place decides whether the extension is allowed to talk to a studio,
// what it talks to, and when the tree is re-authorised:
//
//   1. `validateStudioConfig` runs BEFORE every client is created (the T2
//      pure validation API; no dialog, no persistence, no TLS bypass).
//   2. Plaintext HTTP additionally needs an explicit confirmation scoped to
//      the exact URL, held only for this extension session. Enabling the
//      setting is not consent.
//   3. One connect attempt per generation: concurrent commands share it, so a
//      second plaintext consent dialog or a second event stream is never
//      opened. The SSE handlers are subscribed before `start()`, so the first
//      `open` can trigger the authoritative read; every reconnect does the
//      same, which is what recovers events missed while disconnected.
//   4. A settings change invalidates the generation first (dropping the
//      stream and client) and then revalidates, so a consent that resolves
//      afterwards can never resurrect the replaced URL.
//
// This module never touches the filesystem, the network or VS Code: the
// extension injects those seams. That keeps the state machine testable and
// keeps the consent rule in exactly one place.

import { validateStudioConfig, type StudioConfigInput, type ValidatedStudioConfig } from "./config";
import type { SseClient, SseState, TreeEvent } from "./sseClient";
import type { StudioApi, TreeSnapshot } from "./studioApi";

export type ConnectionStatusKind = "connecting" | "live" | "error" | "disconnected";

export interface ConnectionStatus {
  kind: ConnectionStatusKind;
  detail: string;
}

export interface StudioConnectionDeps {
  readSettings(): StudioConfigInput;
  /** Modal confirmation for plaintext HTTP; returns true only on explicit approval. */
  confirmInsecureHttp(baseUrl: string): Promise<boolean>;
  createApi(config: ValidatedStudioConfig): StudioApi;
  createSse(config: ValidatedStudioConfig): SseClient;
  onStatus(status: ConnectionStatus): void;
  onEvent(event: TreeEvent): void;
  /**
   * Loads the authoritative snapshot. The callee owns buffering: mutations
   * that arrive while the loader is in flight must be queued and replayed
   * after the snapshot is applied (see `StudioTreeProvider.reconcileWith`).
   */
  syncTree(load: () => Promise<TreeSnapshot>): Promise<void>;
  log?(line: string): void;
}

/**
 * Thrown by [StudioConnection.ensureApi] when a request may not be sent.
 * The message is user-facing: it names the rejected setting or the URL that
 * was not approved, never a stack trace.
 */
export class StudioConnectionError extends Error {
  override readonly name = "StudioConnectionError";
}

export class StudioConnection {
  private readonly confirmedUrls = new Set<string>();
  private sse: SseClient | null = null;
  private api: StudioApi | null = null;
  private config: ValidatedStudioConfig | null = null;
  private current: ConnectionStatus = { kind: "connecting", detail: "not connected yet" };
  private disposed = false;
  /** Current connection generation. Invoked whenever the in-flight attempt is
   *  invalidated (settings change or dispose), so a stale resolution can
   *  never assign a client, send with old headers or report `open`. */
  private generation = 0;
  /** The single in-flight connect attempt; concurrent callers share it. */
  private attempt: { token: number; promise: Promise<void> } | null = null;

  constructor(private readonly deps: StudioConnectionDeps) {}

  get status(): ConnectionStatus {
    return this.current;
  }

  /** Current validated configuration, or null when nothing is connected. */
  get activeConfig(): ValidatedStudioConfig | null {
    return this.config;
  }

  /** Start the stream. Safe to call once per extension activation. */
  start(): void {
    void this.connect();
  }

  /**
   * The single gate every command goes through: it validates the settings
   * (and asks for HTTP consent when needed) before returning a client, so a
   * command can never send a request that bypassed validation. Concurrent
   * callers share one attempt: one dialog, one client, one stream.
   */
  async ensureApi(): Promise<StudioApi> {
    if (this.disposed) throw new StudioConnectionError("PipelineK Studio is no longer active.");
    if (this.api) return this.api;
    await this.connect();
    if (this.disposed) throw new StudioConnectionError("PipelineK Studio is no longer active.");
    if (!this.api) {
      throw new StudioConnectionError(this.current.detail);
    }
    return this.api;
  }

  /** Authoritative re-read. Throws so the caller can report the failure. */
  async refreshTree(): Promise<void> {
    const api = this.api;
    if (!api) throw new StudioConnectionError("PipelineK Studio is not connected.");
    try {
      await this.deps.syncTree(() => api.collectTree());
    } catch (e) {
      if (this.disposed) return;
      const detail = `authoritative tree refresh failed: ${messageOf(e)}`;
      this.setStatus({ kind: "error", detail });
      throw new StudioConnectionError(detail);
    }
  }

  /** Revalidate on a settings change: invalidate first, then reconnect. */
  async reload(): Promise<void> {
    if (this.disposed) return;
    this.deps.log?.("settings changed; revalidating Studio configuration");
    this.invalidate();
    await this.connect();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.invalidate();
  }

  /**
   * Drop the current generation: bump the token so anything still resolving
   * for it is inert, dispose the stream (aborting its transport) and forget
   * the client and configuration. Consent already given is untouched: it is
   * scoped to the exact URL, not to the generation.
   */
  private invalidate(): void {
    this.generation += 1;
    this.attempt = null;
    this.closeStream();
    this.api = null;
    this.config = null;
  }

  private isCurrent(token: number): boolean {
    return !this.disposed && token === this.generation;
  }

  /**
   * One attempt per generation. Concurrent callers (a command and the
   * activation `start()`, or two commands) share the same promise, so a
   * second plaintext HTTP dialog or a second stream is never opened.
   */
  private connect(): Promise<void> {
    if (this.disposed) return Promise.resolve();
    if (this.attempt) return this.attempt.promise;
    const token = ++this.generation;
    const promise = this.runConnect(token);
    const attempt = { token, promise };
    this.attempt = attempt;
    void promise.then(
      () => {
        if (this.attempt === attempt) this.attempt = null;
      },
      () => {
        if (this.attempt === attempt) this.attempt = null;
      },
    );
    return promise;
  }

  private async runConnect(token: number): Promise<void> {
    if (!this.isCurrent(token)) return;
    this.setStatus({ kind: "connecting", detail: "validating Studio settings" });

    let config: ValidatedStudioConfig;
    try {
      config = await this.resolveConfig(token);
    } catch (e) {
      if (this.isCurrent(token)) this.setStatus({ kind: "error", detail: messageOf(e) });
      return;
    }
    // The settings may have changed while consent was pending: a resolution
    // for a replaced generation never assigns a client, a header set or a
    // stream, so it cannot resurrect the previous URL.
    if (!this.isCurrent(token)) return;

    this.closeStream();
    const api = this.deps.createApi(config);
    const sse = this.deps.createSse(config);
    this.api = api;
    this.config = config;
    this.sse = sse;

    // Subscribe BEFORE start(): the first `open` must trigger the
    // authoritative read, and no event may fall between handshake and
    // subscription. The token keeps a disposed generation's stream from
    // reporting `open` or delivering events after a settings change.
    sse.onState((state) => this.handleSseState(token, state));
    sse.onEvent((event) => {
      if (this.isCurrent(token)) this.deps.onEvent(event);
    });

    this.setStatus({
      kind: "connecting",
      detail: `connecting to ${config.baseUrl} (profile ${config.profile})`,
    });
    this.deps.log?.(`connecting to ${config.baseUrl} with profile ${config.profile}`);
    sse.start();
  }

  private async resolveConfig(token: number): Promise<ValidatedStudioConfig> {
    let config: ValidatedStudioConfig;
    try {
      config = validateStudioConfig(this.deps.readSettings());
    } catch (e) {
      throw new StudioConnectionError(`invalid Studio configuration: ${messageOf(e)}`);
    }
    const url = new URL(config.baseUrl);
    if (url.protocol === "http:") {
      if (!this.confirmedUrls.has(config.baseUrl)) {
        const approved = await this.deps.confirmInsecureHttp(config.baseUrl);
        if (this.disposed) throw new StudioConnectionError("PipelineK Studio is no longer active.");
        if (!this.isCurrent(token)) {
          throw new StudioConnectionError(
            `Studio settings changed while confirming plaintext HTTP to ${config.baseUrl}; it was not connected.`,
          );
        }
        if (!approved) {
          throw new StudioConnectionError(
            `plaintext HTTP to ${config.baseUrl} was not approved; set pipelinekStudio.baseUrl to an https:// URL or approve HTTP for this session.`,
          );
        }
        // Session-scoped, exact-URL approval: never persisted, never
        // inherited by a different URL or profile host, and only recorded
        // for the generation that is still current.
        this.confirmedUrls.add(config.baseUrl);
      }
    }
    return config;
  }

  private handleSseState(token: number, state: SseState): void {
    if (!this.isCurrent(token)) return;
    switch (state.kind) {
      case "connecting":
        this.setStatus({ kind: "connecting", detail: "opening the event stream" });
        return;
      case "open":
        this.setStatus({ kind: "live", detail: "event stream open" });
        // Every open, including the first: the stream is a live tail, so
        // the only authoritative view is a fresh read of the collections.
        void this.refreshTree().catch(() => undefined);
        return;
      case "backoff":
        this.setStatus({
          kind: "disconnected",
          detail: `event stream lost; retrying in ${Math.round(state.nextAttemptInMs / 1000)}s (attempt ${state.attempt}): ${state.reason}`,
        });
        return;
      case "closed":
        this.setStatus({
          kind: "disconnected",
          detail: state.reason === "disposed" ? "event stream disposed" : `event stream closed: ${state.reason}`,
        });
        return;
    }
  }

  private closeStream(): void {
    const sse = this.sse;
    this.sse = null;
    sse?.dispose();
  }

  private setStatus(status: ConnectionStatus): void {
    if (this.disposed) return;
    this.current = status;
    this.deps.onStatus(status);
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
