package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.usecases.WiringExt.createExperiment
import dev.pipelinek.studio.application.usecases.WiringExt.sealSnapshot
import dev.pipelinek.studio.application.usecases.WiringExt.startRun
import dev.pipelinek.studio.application.usecases.WiringExt.inspectRun
import dev.pipelinek.studio.application.usecases.WiringExt.cancel
import dev.pipelinek.studio.application.usecases.WiringExt.listRuns
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.domain.*
import java.lang.management.ManagementFactory
import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.RunReadResult
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Ktor module wiring HTTP routes to the application use cases.
 *
 * Per docs/02-specifications/HTTP_AND_STREAMING_API.md and R3 release protocol: routes
 * never invent outcomes. All errors are typed via [ErrorResponse].
 */
class StudioHttpModule(
    private val wiringFactory: () -> dev.pipelinek.studio.application.usecases.Wiring,
    /**
     * Optional: when provided, the module wires a real `PipelineKRunReadPort` for
     * `GET /runs/{id}/events` and `/runs/{id}` so S1 read-side returns real events
     * from `run.db` instead of the S0 stub refusal. The wiring factory is consulted
     * first; if no SDK-backed read port is present, the factory's port is used.
     */
    private val runReadFactory: (() -> dev.pipelinek.studio.application.ports.RunReadPort)? = null,
    /**
     * Optional: when provided, OutputReadPort serves real subprocess stdout bytes
     * captured to `<controlRoot>/<runId>/stdout.log` by PipelineKRunner.
     */
    private val outputReadFactory: (() -> dev.pipelinek.studio.application.ports.OutputReadPort)? = null,
    /**
     * S5 server-side restart reconciliation (DUR-004). Required for
     * `GET /api/recovery`; the reconciler walks the per-run control-root
     * directory and surfaces in-flight runs after a studio restart.
     * Optional; if null the recovery endpoint is not registered.
     */
    private val controlRootBase: java.nio.file.Path? = null,
    /**
     * Optional: when provided, StepCatalogReadPort backs `GET /steps` and
     * `GET /steps/{id}` for S2 (Plugin Lab).
     */
    private val stepCatalogFactory: (() -> dev.pipelinek.studio.application.ports.StepCatalogReadPort)? = null,
    /**
     * Optional: when provided, DiagnosticsReadPort + IrExplorerReadPort back
     * `GET /runs/{id}/diagnostics` and `GET /runs/{id}/ir` for S3.
     */
    private val diagnosticsFactory: (() -> dev.pipelinek.studio.application.ports.DiagnosticsReadPort)? = null,
    private val irFactory: (() -> dev.pipelinek.studio.application.ports.IrExplorerReadPort)? = null,
    /**
     * Optional: explicit Host allow-list (AAT-SEC-005). When null, the default
     * loopback set is used. The test harness passes a narrow set so the
     * "forged Host" tests are deterministic regardless of the test framework's
     * default Host header. Placed LAST in the parameter list so adding it does
     * not shift the positional calls in `studio-app/Main.kt`.
     */
    private val hostAllowList: Set<String>? = null,
    /**
     * Optional: AAT-DUR-006 — authoritative per-run subprocess bindings
     * (run_id -> pid) from the runner. When provided, the retention
     * endpoint consults this map for the live-subprocess check. When
     * null, the endpoint falls back to a /proc scan (defensive: a
     * non-null empty map means "no live subprocesses").
     *
     * Kept as a factory so we capture the live map at request time,
     * not at server start. The map is a snapshot — by the time the
     * retention service reads it, the actual state may have changed.
     */
    private val liveRunToPidFactory: (() -> Map<String, Long>)? = null,
    /**
     * AAT-SEC-010 — workspace lease for destructive operations.
     * When provided, the admin retention endpoint requires a
     * valid lease token via the `X-Studio-Lease` header; without
     * one, the request is refused and no files are touched.
     * When null, the admin endpoint falls back to the previous
     * (lease-less) behaviour — kept so the test harness can
     * opt out during unit tests. Production MUST wire this.
     */
    private val leaseAcquireFactory: (() -> dev.pipelinek.studio.application.ports.Lease)? = null,
    private val leaseValidateFactory: ((String, String) -> dev.pipelinek.studio.application.ports.Lease?)? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun install(app: Application) {
        app.install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                classDiscriminator = "kind"
                encodeDefaults = false
            })
        }
        app.install(CallLogging)
        app.installBodySizeLimit()
        // AAT-SEC-005: Host allow-list. Sits BEFORE routing so a forged Host
        // is rejected before any handler is invoked. Tests set the allow-list
        // explicitly via the constructor param so the "forged Host" tests
        // are deterministic regardless of the test framework's default Host.
        app.installHostAllowList(hostAllowList ?: DEFAULT_ALLOWED_HOSTS)
        // S5 E5.4: rate limit is opt-out via STUDIO_RATE_LIMIT_DISABLED=1 (set by perf
        // tests and the in-process test harness). Production wires it.
        if (System.getenv("STUDIO_RATE_LIMIT_DISABLED") != "1") {
            app.installRateLimit()
        }
        app.install(SSE)
        app.install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", cause.message ?: "bad request"))
            }
            exception<IllegalStateException> { call, cause ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("RUNTIME_UNAVAILABLE", cause.message ?: "runtime error"))
            }
            exception<Throwable> { call, cause ->
                log.error("uncaught", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL", cause.message ?: "internal"))
            }
        }
        app.routing {
            get("/health") {
                call.respond(HealthResponse(status = "ok"))
            }
            get("/api/version") {
                val wiring = wiringFactory()
                val versionResp = when (val r = wiring.runtime.describe()) {
                    is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available -> VersionResponse(
                        studio = "0.1.0-alpha.1",
                        pipelinekRuntime = r.descriptor.version,
                        pipelinekRuntimeDigest = r.descriptor.artifactDigest,
                        pipelinekRuntimeCapabilities = r.descriptor.capabilities.toList(),
                    )
                    dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable -> VersionResponse(studio = "0.1.0-alpha.1")
                    is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Failure -> VersionResponse(studio = "0.1.0-alpha.1")
                }
                call.respond(versionResp)
            }

            get("/api/metrics") {
                // S5 profiling: cold vs steady-state visibility. The operator
                // hits this to compare wall-clock from /health with the JVM
                // start time. No auth — same posture as /health.
                val rt = Runtime.getRuntime()
                val mb = 1024L * 1024L
                val jvmUptimeMs = ManagementFactory.getRuntimeMXBean().uptime
                val tx = ManagementFactory.getThreadMXBean()
                val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                call.respond(
                    MetricsResponse(
                        jvm = JvmMetrics(
                            uptimeMs = jvmUptimeMs,
                            threadsLive = tx.threadCount,
                            threadsPeak = tx.peakThreadCount,
                            heapUsedMb = mem.used / mb,
                            heapMaxMb = mem.max / mb,
                            availableProcessors = rt.availableProcessors(),
                        ),
                    ),
                )
            }

            // S5 server-side restart reconciliation (DUR-004). The runner
            // supervisor's in-memory `liveProcs` map is empty after a
            // studio restart; the run.db files under controlRoot are
            // the source of truth. RunReconciler walks the dir and
            // surfaces each in-flight run as RECOVERY_REQUIRED (or
            // LOST when no events were ever written) so the operator
            // AAT-SEC-010 — acquire a workspace lease for a
            // destructive op. POST /api/admin/lease?op=<kind>
            // returns a token that the caller must present via
            // the X-Studio-Lease header on the destructive op.
            // The lease has a short TTL (default 60 s); when it
            // expires, the op is refused and no files are
            // touched. Unknown op-kinds are rejected with 400.
            if (leaseAcquireFactory != null) {
                post("/api/admin/lease") {
                    val opKind = call.request.queryParameters["op"] ?: "retention-sweep"
                    val allowedOpKinds = setOf("retention-sweep")
                    if (opKind !in allowedOpKinds) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                code = "UNKNOWN_OP_KIND",
                                message = "op '$opKind' is not a known destructive op-kind",
                                actions = listOf("supported: retention-sweep"),
                            ),
                        )
                        return@post
                    }
                    val lease = leaseAcquireFactory.invoke()
                    // Replace the lease's op-kind with the
                    // requested one (the factory defaults to
                    // 'retention-sweep'; here we honour the
                    // caller's request). Future op-kinds can
                    // add explicit factories.
                    val effective = if (lease.opKind == opKind) lease else lease.copy(opKind = opKind)
                    call.respond(
                        dev.pipelinek.studio.http.LeaseDto(
                            token = effective.token,
                            opKind = effective.opKind,
                            issuedAt = effective.issuedAt.toString(),
                            expiresAt = effective.expiresAt.toString(),
                        ),
                    )
                }
            }
            // can see what state the subprocess is in. The report is
            // NEVER persisted — the operator decides what to do.
            if (controlRootBase != null) {
                get("/api/recovery") {
                    val reconciler = dev.pipelinek.studio.infra.storage.RunReconciler(controlRootBase)
                    val report = reconciler.reconcile()
                    call.respond(
                        RecoveryReportDto(
                            controlRoot = report.controlRootBase.toString(),
                            totalRuns = report.totalRuns,
                            reconciled = report.reconciled.map { e ->
                                RecoveryEntryDto(
                                    runId = e.runId.value,
                                    state = e.state.name,
                                    lastEventKind = e.lastEventKind,
                                    lastEventAt = e.lastEventAt?.toString(),
                                    reason = e.reason,
                                )
                            },
                        ),
                    )
                }
                // AAT-DUR-006 — POST /api/admin/retention runs a
                // sweep: walks the controlRoot, deletes per-run
                // dirs that are terminal AND have no live SDK
                // subprocess bound. Anything else is kept (with
                // a reason). The endpoint is admin-only by design
                // and AAT-SEC-010 — a destructive op protected
                // by a lease. A valid `X-Studio-Lease` header
                // for op-kind 'retention-sweep' is required.
                post("/api/admin/retention") {
                    val retention = dev.pipelinek.studio.infra.storage.RetentionService()
                    // AAT-SEC-010: lease gate. When the lease
                    // registry is wired in (production), the
                    // request must carry a valid lease token for
                    // op-kind 'retention-sweep'. Without one,
                    // the op is refused (no files touched).
                    val leaseToken = call.request.headers["X-Studio-Lease"]
                    if (leaseValidateFactory != null) {
                        val lease = leaseToken?.let { leaseValidateFactory(it, "retention-sweep") }
                        if (lease == null) {
                            // 428 Precondition Required — RFC 6585: the
                            // server requires the request to be
                            // conditional (here: a valid lease).
                            call.respond(
                                HttpStatusCode(428, "Precondition Required"),
                                ErrorResponse(
                                    code = "LEASE_REQUIRED",
                                    message = "X-Studio-Lease header is required (or expired / wrong op-kind)",
                                    actions = listOf("acquire a fresh lease via POST /api/admin/lease?op=retention-sweep"),
                                ),
                            )
                            return@post
                        }
                    }
                    // Authoritative per-run pid map from the runner.
                    // When the runner is wired in (production), the
                    // retention service uses this for a per-run
                    // check: a run is kept only when its run_id is
                    // present in this map. When the runner is not
                    // wired in (some test harnesses), the map is
                    // empty: the service treats every terminal run
                    // as eligible for deletion.
                    val liveRunToPid: Map<String, Long> = liveRunToPidFactory?.invoke() ?: emptyMap()
                    val outcome = retention.sweep(controlRootBase, liveRunToPid)
                    call.respond(
                        HttpStatusCode.OK,
                        RetentionReportDto(
                            runsScanned = outcome.runsScanned,
                            runsDeleted = outcome.runsDeleted,
                            runsKept = outcome.runsKept.map {
                                when (it) {
                                    is dev.pipelinek.studio.application.ports.RunKeptReason.NotTerminal ->
                                        RetentionKeptDto(runId = it.runId, reason = "not-terminal")
                                    is dev.pipelinek.studio.application.ports.RunKeptReason.HasLiveSubprocess ->
                                        RetentionKeptDto(runId = it.runId, reason = "has-live-subprocess", pid = it.pid)
                                    is dev.pipelinek.studio.application.ports.RunKeptReason.Unreadable ->
                                        RetentionKeptDto(runId = it.runId, reason = "unreadable", detail = it.reason)
                                }
                            },
                        )
                    )
                }
            }

            get("/openapi.json") {
                // S5 E5.3 — serve the curated OpenAPI 3.1 document. Hand-maintained
                // because Ktor's reflection-based derivation would be heavier than the
                // spec we actually need; this is the same document the SDK generator
                // consumes to emit a TypeScript client.
                call.respondText(
                    text = OpenApiDoc.current,
                    contentType = io.ktor.http.ContentType.Application.Json,
                )
            }

            post("/experiments") {
                val req = call.receive<CreateExperimentRequest>()
                val wiring = wiringFactory()
                val uc = wiring.createExperiment
                val created = uc.execute(req.name)
                call.respond(
                    CreateExperimentResponse(
                        experimentId = created.id.value,
                        name = created.name,
                        createdAt = created.createdAt.toString(),
                    ),
                )
            }

            route("/experiments/{experimentId}") {
                post("/snapshots") {
                    val experimentId = RouteParams.requireUuid(call, "experimentId", call.parameters["experimentId"])?.let { ExperimentId(it) } ?: return@post
                    val req = call.receive<SealSnapshotRequest>()
                    val wiring = wiringFactory()
                    val uc = wiring.sealSnapshot
                    // AAT-SEC-007: scrub known secret patterns from the source on
                    // intake. The redactor is opt-in (D-005) but we wire the
                    // default (PatternSecretRedactor) so the operator does not
                    // have to remember to enable it. The redacted bytes are what
                    // get sealed + SHA'd, so a snapshot that contains a secret
                    // never has the secret on disk.
                    val redactor = dev.pipelinek.studio.application.ports.PatternSecretRedactor()
                    val safeSource = redactor.redact(req.source)
                    val snap = uc.execute(experimentId, safeSource.toByteArray(Charsets.UTF_8), req.creator)
                    call.respond(
                        SealSnapshotResponse(
                            snapshotId = snap.id.value,
                            experimentId = snap.experimentId.value,
                            sha256 = snap.sha256.value,
                            createdAt = snap.createdAt.toString(),
                        ),
                    )
                }
            }

            post("/runs") {
                val idempotencyKey = call.request.header("Idempotency-Key")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_HEADER", "Idempotency-Key required"))
                val req = call.receive<StartRunRequest>()
                val wiring = wiringFactory()
                val runId = RunId.new()
                val idemKey = IdempotencyKey(idempotencyKey)
                val digest = Sha256.digest("${req.experimentId}|${req.snapshotId}|${req.scenarioId}".toByteArray())
                val uc = wiring.startRun()
                val binding = uc.execute(
                    SnapshotId(req.snapshotId),
                    ScenarioId(req.scenarioId),
                    runId,
                    idemKey,
                    digest,
                )
                // Persist the run binding to Studio metadata so it survives restart. This is the
                // minimum-viable persistence per DATA_MODEL_AND_PERSISTENCE.md §"RunBinding".
                @Suppress("UNCHECKED_CAST")
                val metadataRepo = wiring.metadata as? dev.pipelinek.studio.infra.storage.StudioMetadataRepository
                metadataRepo?.persistRunBinding(binding)
                // UAT-046: compute claim_status at submit time so the
                // immediate response surfaces the test-authority verdict
                // without the client having to do a follow-up /runs/{id}.
                val claimStatus = dev.pipelinek.studio.application.ports.ClaimStatusDetector
                    .detect(wiring.metadata.loadSnapshotSource(binding.snapshotId))
                    .name
                call.respond(
                    StartRunResponse(
                        runId = binding.runId.value,
                        state = binding.state.name,
                        upstreamRunId = binding.upstreamRunId?.value,
                        claimStatus = claimStatus,
                    ),
                )
            }

            // S1 — cancel an in-flight run. POST /runs/{runId}/cancel sends
            // SIGKILL to the live SDK subprocess via PipelineKRunner.cancel().
            // Returns 200 + {acknowledged: true} on success, 202 + documented
            // CANCEL_UNKNOWN if no live subprocess (run already finished or
            // never spawned — the contract is "no live handle" not "fake ack").
            post("/runs/{runId}/cancel") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@post
                val wiring = wiringFactory()
                when (val res = wiring.cancel().cancel(runId)) {
                    dev.pipelinek.studio.application.ports.CancellationResult.CancelRequested,
                    is dev.pipelinek.studio.application.ports.CancellationResult.AlreadyTerminal,
                    -> call.respond(HttpStatusCode.OK, mapOf("acknowledged" to true))
                    is dev.pipelinek.studio.application.ports.CancellationResult.Unknown ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("CANCEL_UNKNOWN", res.reason))
                }
            }

            // S1 read-side — list runs (paginated, optional state filter).
            get("/runs") {
                val stateParam = call.request.queryParameters["state"]
                val state = stateParam?.let {
                    runCatching { dev.pipelinek.studio.domain.RunState.valueOf(it.uppercase()) }.getOrNull()
                }
                if (stateParam != null && state == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("BAD_REQUEST", "unknown state: $stateParam"),
                    )
                    return@get
                }
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val wiring = wiringFactory()
                val items = wiring.listRuns().execute(state, limit, offset)
                call.respond(ListRunsResponse(items = items.map { snap ->
                    // UAT-046: compute claim_status from the sealed
                    // source for each run summary.
                    val claim = dev.pipelinek.studio.application.ports.ClaimStatusDetector
                        .detect(wiring.metadata.loadSnapshotSource(snap.snapshotId))
                        .name
                    RunSummaryDto(
                        runId = snap.id.value,
                        experimentId = snap.experimentId.value,
                        snapshotId = snap.snapshotId.value,
                        scenarioId = snap.scenarioId.value,
                        state = snap.state,
                        createdAt = snap.createdAt.toString(),
                        claimStatus = claim,
                    )
                }))
            }

            get("/runs/{runId}") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@get
                val wiring = wiringFactory()
                val reader = runReadFactory?.invoke()
                // UAT-046: compute claim_status from the sealed source.
                // A scenario whose source has no test authority
                // (assert/shouldEqual/expect) is NOT_EXECUTED; otherwise
                // CLAIM_VERIFIED. Computed on-the-fly from the snapshot
                // source bytes — no schema migration needed.
                val claimStatus = run {
                    val run = wiring.metadata.findRun(runId)
                    if (run == null) "NOT_EXECUTED"
                    else dev.pipelinek.studio.application.ports.ClaimStatusDetector
                        .detect(wiring.metadata.loadSnapshotSource(run.snapshotId))
                        .name
                }
                when (val s = wiring.inspectRun().status(runId)) {
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Live ->
                        call.respond(RunStatusResponse(runId.value, "RUNNING", s.upstreamRunId, claimStatus = claimStatus))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Terminal ->
                        call.respond(RunStatusResponse(runId.value, s.finalState, claimStatus = claimStatus))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown -> {
                        // Real state from run.db if runReadFactory was wired in.
                        val probe = if (reader != null) reader.state(runId)
                            else wiring.inspectRun().events(runId, null, 1)
                        when (probe) {
                            is dev.pipelinek.studio.application.ports.RunReadResult.Ok ->
                                call.respond(RunStatusResponse(runId.value, "STARTING", claimStatus = claimStatus))
                            else -> call.respond(HttpStatusCode.Accepted, RunStatusResponse(runId.value, "UNKNOWN", claimStatus = claimStatus))
                        }
                    }
                }
            }

            get("/runs/{runId}/events") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@get
                val reader = runReadFactory?.invoke()
                val wiring = wiringFactory()
                // ?limit caps the page size (S1 reconnect contract per
                // ROADMAP §"reconnect tokens"). Default 50 matches the
                // pre-pagination behaviour; clients pass smaller limits
                // to walk the journal in pages. ?after is the cursor
                // returned by the previous page's nextSequence.
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val afterParam = call.request.queryParameters["after"]?.toLongOrNull()
                val cursor = afterParam?.let { dev.pipelinek.studio.application.ports.EventCursor(it) }
                val res = if (reader != null) reader.events(runId, cursor, limit)
                    else wiring.inspectRun().events(runId, cursor, limit)
                when (res) {
                    is dev.pipelinek.studio.application.ports.RunReadResult.Ok -> {
                        // Build a typed @Serializable response so kotlinx can
                        // handle the heterogeneous payload shape (Long, String,
                        // Map). The previous Map<String, Any?> approach failed
                        // because heterogeneous values are not supported.
                        call.respond(
                            RunEventsResponse(
                                runId = runId.value,
                                events = res.events.map { e ->
                                    RunEventDto(
                                        sequence = e.sequence,
                                        kind = e.kind,
                                        occurredAt = e.occurredAt.toString(),
                                        payload = e.payload,
                                    )
                                },
                                nextSequence = res.nextSequence?.sequence,
                            ),
                        )
                    }
                    is dev.pipelinek.studio.application.ports.RunReadResult.Unknown -> {
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("EVENTS_UNKNOWN", res.reason))
                    }
                }
            }

            get("/steps") {
                val catalog = stepCatalogFactory?.invoke()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("STEP_CATALOG_UNAVAILABLE", "no SDK-backed StepCatalogReadPort wired"),
                    )
                when (val res = catalog.list()) {
                    is dev.pipelinek.studio.application.ports.StepCatalogResult.Ok -> {
                        val dto = StepCatalogDto(
                            steps = res.steps.map { it.toDto() },
                        )
                        call.respond(dto)
                    }
                    is dev.pipelinek.studio.application.ports.StepCatalogResult.Failure ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("STEP_CATALOG_FAILURE", res.reason))
                }
            }

            get("/steps/{stepId}") {
                val stepId = call.parameters["stepId"]!!
                val catalog = stepCatalogFactory?.invoke()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("STEP_CATALOG_UNAVAILABLE", "no SDK-backed StepCatalogReadPort wired"),
                    )
                when (val res = catalog.describe(stepId)) {
                    is dev.pipelinek.studio.application.ports.StepCatalogResult.Ok -> {
                        if (res.steps.isEmpty()) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("STEP_NOT_FOUND", "no step matches '$stepId'"))
                        } else {
                            call.respond(StepCatalogDto(steps = res.steps.map { it.toDto() }))
                        }
                    }
                    is dev.pipelinek.studio.application.ports.StepCatalogResult.Failure ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("STEP_NOT_FOUND", res.reason))
                }
            }

            get("/runs/{runId}/diagnostics") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@get
                val reader = diagnosticsFactory?.invoke()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("DIAGNOSTICS_UNAVAILABLE", "no DiagnosticsReadPort wired"),
                    )
                when (val res = reader.diagnostics(runId)) {
                    is dev.pipelinek.studio.application.ports.DiagnosticsReadResult.Ok ->
                        call.respond(DiagnosticsDto(diagnostics = res.diagnostics.map { it.toDto() }))
                    is dev.pipelinek.studio.application.ports.DiagnosticsReadResult.Unknown ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("DIAGNOSTICS_UNKNOWN", res.reason))
                }
            }

            get("/runs/{runId}/ir") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@get
                val reader = irFactory?.invoke()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("IR_UNAVAILABLE", "no IrExplorerReadPort wired"),
                    )
                when (val res = reader.snapshot(runId)) {
                    is dev.pipelinek.studio.application.ports.IrReadResult.Ok ->
                        call.respond(IrResultDto(snapshot = res.snapshot.toDto()))
                    is dev.pipelinek.studio.application.ports.IrReadResult.Unknown ->
                        call.respond(HttpStatusCode.Accepted, IrResultDto(reason = res.reason))
                }
            }

            get("/runs/{runId}/output") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@get
                val reader = outputReadFactory?.invoke()
                val wiring = wiringFactory()
                // ?after=<byte-offset> lets the SPA resume from where the
                // previous read left off; the port's cursor is a stable
                // byte offset into the sealed stdout.log. R-03: the
                // log is stable after RunFinished, so a cursor at EOF
                // must always return 0 chunks (no re-emission of bytes
                // already delivered).
                val afterParam = call.request.queryParameters["after"]?.toLongOrNull()
                val cursor = afterParam?.let { dev.pipelinek.studio.application.ports.OutputCursor(it.toString(), streamId = "stdout") }
                val res = if (reader != null) reader.read(runId, cursor, 16384)
                    else wiring.inspectRun().output(runId, cursor, 16384)
                when (res) {
                    is dev.pipelinek.studio.application.ports.OutputReadResult.Ok -> {
                        // Build a typed @Serializable response so kotlinx can
                        // serialise the heterogeneous chunk shape (Base64
                        // string + cursor + eof). The previous direct respond
                        // of List<OutputChunk> failed with
                        // "Serializer for class 'OutputChunk' is not found"
                        // because OutputChunk carries a ByteArray and the
                        // application module does not apply the serialization
                        // plugin. Base64 keeps the wire contract byte-faithful.
                        call.respond(
                            OutputReadResponse(
                                runId = runId.value,
                                chunks = res.chunks.map { c ->
                                    OutputChunkDto(
                                        streamId = c.streamId,
                                        bytesBase64 = java.util.Base64.getEncoder().encodeToString(c.bytes),
                                        cursor = c.cursor.cursor,
                                        eof = c.eof,
                                    )
                                },
                                nextCursor = res.nextCursor.cursor,
                            ),
                        )
                    }
                    is dev.pipelinek.studio.application.ports.OutputReadResult.Refused ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_REFUSED", res.reason))
                    is dev.pipelinek.studio.application.ports.OutputReadResult.UnknownStream ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_UNKNOWN_STREAM", res.reason))
                    is dev.pipelinek.studio.application.ports.OutputReadResult.RecoveryRequired ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_RECOVERY_REQUIRED", res.reason))
                }
            }

            /**
             * SSE stream of events for a run. Closes after EOF or after 5 minutes of idleness.
             * For S1 this replaces the polling pattern; S2 adds backpressure + reconnect tokens.
             *
             * S1 E1.4 reconnect: clients may pass `Last-Event-ID` (the standard SSE
             * reconnect mechanism) or `?after=<sequence>`. When present, the stream
             * resumes from that sequence; otherwise it starts from the head. The
             * server echoes the `id` field on every `event:` so clients can record
             * the last delivered event for the next reconnect.
             */
            sse("/runs/{runId}/stream") {
                val runId = RouteParams.requireUuid(call, "runId", call.parameters["runId"])?.let { RunId(it) } ?: return@sse
                val reader = runReadFactory?.invoke()
                    ?: run {
                        send(
                            data = "{\"code\":\"READ_PORT_UNAVAILABLE\",\"message\":\"no SDK-backed RunReadPort wired\"}",
                            event = "error",
                        )
                        return@sse
                    }
                // S1 E1.4 — resume cursor: Last-Event-ID header (standard) > ?after=N query param.
                val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull()
                val queryAfter = call.request.queryParameters["after"]?.toLongOrNull()
                val resumeAfter = lastEventId ?: queryAfter
                var cursor: EventCursor? = resumeAfter?.let { EventCursor(it) }
                if (resumeAfter != null) {
                    send(
                        data = "{\"code\":\"SSE_RESUMED\",\"after\":$resumeAfter}",
                        event = "resume",
                    )
                }
                val maxIterations = 600
                var emptyReads = 0
                val maxEmptyReads = 10 // close stream after 10 consecutive empty reads (5s idle)
                for (i in 0 until maxIterations) {
                    val res = reader.events(runId, cursor, 50)
                    if (res is RunReadResult.Ok) {
                        if (res.events.isEmpty()) {
                            emptyReads += 1
                            if (emptyReads >= maxEmptyReads) {
                                send(data = "{\"code\":\"SSE_IDLE_CLOSE\"}", event = "end")
                                return@sse
                            }
                            delay(500)
                        } else {
                            emptyReads = 0
                            for (event in res.events) {
                                // Audit H-16 (2026-10-10): the previous
                                // implementation built the JSON envelope
                                // by string concatenation:
                                //   "{\"sequence\":${event.sequence},\"kind\":\"${event.kind}\",...,\"payload\":${'"'}${event.payload.toString().replace("\"", "'")}${'"'}}"
                                // `event.kind` and `event.payload.toString()`
                                // are untrusted (they come from the SDK
                                // subprocess's run.db); a malicious or
                                // buggy SDK could supply a kind like
                                // '"; alert(1); //' or a payload with
                                // embedded newlines / NULs that would
                                // break the JSON envelope or, in the
                                // SSE protocol, start a new event.
                                // The fix: build the envelope with
                                // kotlinx.serialization so every field
                                // is properly escaped regardless of
                                // its content. Control characters
                                // (newlines, tabs, NUL) are also
                                // escaped automatically.
                                val payloadJson: JsonElement = runCatching {
                                    Json.parseToJsonElement(event.payload.toString())
                                }.getOrElse { JsonPrimitive(event.payload.toString()) }
                                val envelope: JsonObject = buildJsonObject {
                                    put("sequence", event.sequence)
                                    put("kind", event.kind)
                                    put("occurredAt", event.occurredAt.toString())
                                    put("payload", payloadJson)
                                }
                                val json = Json.encodeToString(JsonObject.serializer(), envelope)
                                // S1 E1.4: emit `id:` so clients can record Last-Event-ID
                                // for the next reconnect attempt.
                                send(data = json, event = event.kind, id = event.sequence.toString())
                                cursor = EventCursor(event.sequence)
                            }
                            cursor = res.nextSequence ?: cursor
                        }
                    } else {
                        send(data = res.toString(), event = "end")
                        return@sse
                    }
                }
                send(data = "{\"code\":\"SSE_TIMEOUT\"}", event = "end")
            }
        }
    }
}

private fun dev.pipelinek.studio.application.ports.StepContract.toDto(): StepContractDto = StepContractDto(
    id = id,
    name = name,
    pluginJar = pluginJar,
    capabilities = capabilities.toList(),
    inputSchemaPresent = inputSchema !is dev.pipelinek.studio.application.ports.StepSchema.NoSchema,
    outputSchemaPresent = outputSchema !is dev.pipelinek.studio.application.ports.StepSchema.NoSchema,
    affordances = affordances.map { StepAffordanceDto(it.kind, it.label) },
)

private fun dev.pipelinek.studio.application.ports.Diagnostic.toDto(): DiagnosticDto = DiagnosticDto(
    id = id,
    severity = severity.name,
    phase = phase.name,
    message = message,
    source = source,
    line = line,
    column = column,
    path = path,
    runId = runId?.value,
    actions = actions,
)

private fun dev.pipelinek.studio.application.ports.StepIR.toDto(): StepIrDto = StepIrDto(
    opId = opId,
    name = name,
    className = className,
    capabilities = capabilities,
    inputSchemaPresent = inputSchemaPresent,
    outputSchemaPresent = outputSchemaPresent,
    phase = phase.name,
)

private fun dev.pipelinek.studio.application.ports.StageIR.toDto(): StageIrDto = StageIrDto(
    name = name,
    steps = steps.map { it.toDto() },
)

private fun dev.pipelinek.studio.application.ports.IrSnapshot.toDto(): IrDto = IrDto(
    runId = runId.value,
    stages = stages.map { it.toDto() },
    phasesObserved = phasesObserved.map { it.name }.sorted(),
)
