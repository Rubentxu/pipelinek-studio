@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.FabricStartIntentReservation
import dev.pipelinek.studio.application.ports.FabricStartIntentStore
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.Rejection
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunParameter
import io.jenkins.pipelinek.fabric.contracts.model.RunParameters
import io.jenkins.pipelinek.fabric.contracts.model.StartRunId
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest
import java.security.MessageDigest

/**
 * The `pipelineKey` on the wire: the package digest, never a paths root.
 *
 * A file-private function rather than a literal in two places so the
 * [FabricStartRequestFingerprint] cannot silently disagree with what
 * [FabricRunnerAdapter.submit] actually sends.
 */
private fun pipelineKeyOf(packageDigest: String): String = "package:$packageDigest"

/** The `displayName` on the wire: derived from the Studio id, nothing else. */
private fun displayNameOf(studioRunId: RunId): String = "studio:${studioRunId.value}"

/**
 * The first port of the H2.2c contract: a [FabricRunnerPort] that
 * answers over Fabric's [RunControl].
 *
 * ## What the adapter does, in one paragraph
 *
 * A Studio caller hands the adapter a [RunRequest]. The adapter
 *
 *  1. resolves the [StartRunRequest.packageDigest] from the injected
 *     [PackageRegistry] (a `null` answer is the adapter's own
 *     [FabricSubmissionResult.Refused] and nothing else is touched —
 *     the snapshot is resolved **before** any identity work);
 *  2. computes the canonical fingerprint of the request content and
 *     asks the injected [FabricStartIntentStore] to reserve the
 *     durable Fabric [StartRunRequest.commandId] for this Studio run;
 *     every arm but `Reserved` is a refusal and `RunControl.start` is
 *     never called (P0-03 / spec S4);
 *  3. puts the **persisted** command identity on the wire — never a
 *     freshly minted one — so a retry after a timeout, a recreated
 *     adapter or a process restart re-uses the same id;
 *  4. sets [StartRunRequest.studioRunId] to the Studio's [RunId] so
 *     Fabric can echo it back without trusting the response;
 *  5. sets [StartRunRequest.displayName] to `"studio:<runId>"` so a
 *     Fabric-side log can name the run without reverse-resolving the
 *     id;
 *  6. leaves [StartRunRequest.parameters] empty because the scenario
 *     descriptor travels **inside** the package, not alongside it
 *     (H2 contract, [sfw-h2-snapshot-package-contract.md] §"The
 *     3-identities rule").
 *
 * ## Durable command identity (P0-03 / S4)
 *
 * The audited defect: the adapter minted a fresh `commandId` per
 * `submit`, so a retry after a timeout carried a different identity,
 * Fabric saw two runs, and a crash lost the identity entirely. S4
 * says *"Persistir StartRunId antes de enviar y reutilizarlo para la
 * misma petición."* The adapter therefore **reserves before it
 * sends**: [FabricStartRequestFingerprint] canonicalises the content,
 * [FabricStartIntentStore.reserve] commits the identity, and only the
 * `Reserved.commandId` reaches the wire.
 *
 * The three refusals that make a remote effect impossible:
 *
 *  - `Conflict` — this Studio run already committed a *different*
 *    content fingerprint; the stored identity is left untouched and
 *    the contradictory request is refused;
 *  - `MissingBinding` — no persisted Studio run binding exists, so no
 *    identity may be minted;
 *  - `Failure` — the reservation (or the read inside it) failed; a
 *    storage failure and an unexpected exception are both refusals,
 *    never a silent in-memory or random-per-send fallback.
 *
 * The reservation is **not** undone on a transport failure: the
 * identity stays persisted so the next attempt reuses it. Nothing
 * here retries automatically — repeated actual dispatch is the Fabric
 * producer's dispatch-idempotency defect (P0-05, blocked), not
 * something a stable `commandId` makes safe.
 *
 * The reply is mapped: [CommandResult.Accepted] →
 * [FabricSubmissionResult.Admitted] with both identities;
 * [CommandResult.Rejected] → [FabricSubmissionResult.Refused] with a
 * human-readable reason (the [Rejection] is stringified so the HTTP
 * layer can render it without a Fabric type on the response surface);
 * [FabricTransportFailure] → [FabricSubmissionResult.Refused] (the
 * seam, see [FabricTransportFailure] KDoc).
 *
 * ## Why the [PackageRegistry] is a constructor parameter
 *
 * H2.3 owns the persistence (the SQLite-backed `(snapshotId,
 * packageDigest)` table). The H2.2c adapter declares the seam and
 * leaves the backing store to the next work-unit. The unit tests
 * pass an in-memory map; production wires a SQLite implementation.
 *
 * ## Cancellation identity (P0-04 / S5)
 *
 * Fabric run ids are **server-assigned** and unrelated to the Studio's
 * [RunId]s; the caller-minted [StartRunRequest.commandId] is a third,
 * different identity. [cancel] therefore resolves the persisted Fabric
 * identity through [bindingLookup] and puts that value — and nothing
 * else — on the wire. Three cases make a remote cancel impossible, and
 * all three refuse *without* calling [RunControl.cancel]:
 *
 *  1. the lookup answers `null` (no binding) →
 *     [FabricCancellationResult.Unknown];
 *  2. the lookup answers a blank id →
 *     [FabricCancellationResult.Unknown];
 *  3. the lookup itself fails (storage error) →
 *     [FabricCancellationResult.Refused] whose reason names the
 *     failure, because "we could not read the record" and "there is no
 *     record" are different facts.
 *
 * Rationale (spec S5): *"Resolver el FabricRunId persistido y cancelar
 * exclusivamente mediante ese identificador."* Casting the Studio id
 * into a Fabric id would address the wrong run — or no run — while
 * reporting success.
 *
 * @param runControl Fabric's [RunControl] (a [io.jenkins.pipelinek.fabric.control.client.FabricClient]
 *   in production, a fake in unit tests). Required.
 * @param packageRegistry the [SnapshotId] → package-digest resolver.
 *   Required — a null return is the adapter's own
 *   [FabricSubmissionResult.Refused].
 * @param bindingLookup the [RunId] → Fabric `RunId` resolver that
 *   [cancel] cancels through. Defaults to the **fail-closed**
 *   `BindingLookup { null }` so a two-argument construction (a legacy
 *   caller, a unit test that never cancels) can never turn the Studio
 *   id into a Fabric id: with the default, [cancel] answers
 *   [FabricCancellationResult.Unknown] and makes no remote call. A
 *   caller that wants to cancel injects a real binding (production:
 *   the SQLite-backed `RunBindingsH2Store`).
 * @param startIntentStore the durable start-intent journal that
 *   reserves the Fabric `commandId` before dispatch (P0-03). Defaults
 *   to `null`, and `null` is **fail-closed**: [submit] answers
 *   [FabricSubmissionResult.Refused] without calling
 *   [RunControl.start], because an unpersisted identity cannot
 *   dispatch a remote effect. There is deliberately no in-memory
 *   default — a process-local journal would answer "no record" after
 *   a restart, which is the exact defect S4 names. Production wires
 *   the SQLite-backed `SqliteFabricStartIntentStore`.
 */
class FabricRunnerAdapter(
    private val runControl: RunControl,
    private val packageRegistry: PackageRegistry,
    private val bindingLookup: BindingLookup = BindingLookup { null },
    private val startIntentStore: FabricStartIntentStore? = null,
) : FabricRunnerPort {

    override suspend fun submit(req: RunRequest): FabricSubmissionResult {
        // (1) Resolve the package digest FIRST. If the snapshot is not
        // registered, the adapter refuses without touching the
        // journal and without contacting Fabric at all — a missing
        // registration is a Studio-side bug, not a server-side
        // refusal.
        val packageDigest: String? = packageRegistry.packageDigestFor(req.snapshotId)
        if (packageDigest == null) {
            return FabricSubmissionResult.Refused(
                "no package registered for snapshot ${req.snapshotId.value}",
            )
        }

        // (2) Reserve the durable command identity BEFORE the remote
        // call. Only the persisted value may be sent: a fresh id per
        // send is the audited defect (a retry becomes a second run).
        val commandId = when (val reservation = reserveStartIntent(req, packageDigest)) {
            is FabricStartIntentReservation.Reserved -> reservation.commandId
            is FabricStartIntentReservation.Conflict ->
                return FabricSubmissionResult.Refused(
                    "start intent conflict: ${reservation.reason}",
                )
            is FabricStartIntentReservation.MissingBinding ->
                return FabricSubmissionResult.Refused(
                    "no persisted run binding for studio run " +
                        "${reservation.studioRunId.value}: refusing to dispatch",
                )
            is FabricStartIntentReservation.Failure ->
                return FabricSubmissionResult.Refused(
                    "start intent reservation failed: ${reservation.reason}",
                )
        }

        // (3) + (4) + (5) + (6). The commandId is the ONLY thing
        // Fabric uses to de-dup retries, and it is now the persisted
        // one; two submits of the SAME request therefore carry the
        // SAME id (see FabricSubmitIdentityP0Test).
        val fabricRequest = StartRunRequest(
            commandId = StartRunId(commandId.value),
            pipelineKey = pipelineKeyOf(packageDigest),
            displayName = displayNameOf(req.runId),
            parameters = RunParameters.EMPTY,
            correlation = null,
            packageDigest = packageDigest,
            studioRunId = req.runId.value,
        )

        return try {
            when (val reply = runControl.start(fabricRequest)) {
                is CommandResult.Accepted -> FabricSubmissionResult.Admitted(
                    studioRunId = req.runId,
                    fabricRunId = reply.value,
                )
                is CommandResult.Rejected -> mapRejection(reply.reason)
            }
        } catch (failure: FabricTransportFailure) {
            // The seam: a transport failure is a caller-visible
            // Refusal, not Unknown. See FabricTransportFailure KDoc.
            FabricSubmissionResult.Refused(
                "transport failure on RunControl.start: ${failure.message}",
            )
        } catch (failure: Exception) {
            // Any other unexpected failure is a Studio-side bug.
            // Refusing (rather than crashing) keeps the HTTP layer
            // honest — a 500 is a 500, not a silent Unknown.
            FabricSubmissionResult.Refused(
                "unexpected error on RunControl.start: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * Ask the journal for this request's durable command identity.
     *
     * Returns the typed reservation unchanged; the only translation is
     * the missing-store case (a construction that never wired a
     * journal) and an exception escaping the store, both of which are
     * failures — never a permission to mint an identity locally.
     */
    private fun reserveStartIntent(
        req: RunRequest,
        packageDigest: String,
    ): FabricStartIntentReservation {
        val store = startIntentStore
            ?: return FabricStartIntentReservation.Failure(
                "no durable start-intent store is wired (a command identity cannot be " +
                    "persisted, so studio run ${req.runId.value} must not dispatch)",
            )
        return try {
            store.reserve(req.runId, FabricStartRequestFingerprint.of(req, packageDigest))
        } catch (failure: Exception) {
            // A read/storage exception is a refusal to dispatch, for
            // the same reason a typed `Failure` is: "we could not read
            // the journal" must never degrade into "we minted one".
            FabricStartIntentReservation.Failure(
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    override suspend fun cancel(studioRunId: RunId): FabricCancellationResult {
        // P0-04: resolve the *persisted* Fabric identity first. The
        // Studio id is not a Fabric id — Fabric assigns its own run ids
        // and the caller-minted commandId is a third identity again — so
        // a cancel that put the Studio id on the wire would address the
        // wrong run (or none) while claiming an effect.
        return when (val target = resolveCancellationTarget(studioRunId)) {
            is CancellationTarget.NotDispatched -> target.result
            is CancellationTarget.Resolved -> dispatchCancel(target.fabricRunId)
        }
    }

    /**
     * The two outcomes of the (read-only) binding resolution: a Fabric
     * identity to cancel through, or a result to return **without** a
     * remote call. Kept separate from [dispatchCancel] so a storage
     * failure is never conflated with a failure of the remote call.
     */
    private sealed interface CancellationTarget {
        data class Resolved(val fabricRunId: FabricRunId) : CancellationTarget
        data class NotDispatched(val result: FabricCancellationResult) : CancellationTarget
    }

    /**
     * Resolve [studioRunId] to the persisted Fabric run identity, or
     * answer "do not dispatch" with a truthful reason.
     *
     * Three cases make a remote cancel impossible, and none of them is
     * allowed to fall back to the Studio id:
     *
     *  - the lookup answers `null` — there is no record → `Unknown`;
     *  - the lookup answers a blank id — the record cannot address a
     *    Fabric run → `Unknown`;
     *  - the lookup throws — the record could not be read → `Refused`,
     *    because "we could not read it" and "it does not exist" are
     *    different facts a caller must be able to tell apart.
     */
    private fun resolveCancellationTarget(studioRunId: RunId): CancellationTarget =
        try {
            val resolved = bindingLookup.fabricRunIdFor(studioRunId)
            if (resolved == null) {
                CancellationTarget.NotDispatched(
                    FabricCancellationResult.Unknown(
                        "no binding for studioRunId ${studioRunId.value} (the run was never submitted, " +
                            "or it was bound to a different Fabric server)",
                    ),
                )
            } else if (resolved.value.isBlank()) {
                // A blank stored id addresses nothing. Fabric's RunId
                // carries no invariant, so this is reachable; refusing
                // here keeps a corrupt binding from becoming a call.
                CancellationTarget.NotDispatched(
                    FabricCancellationResult.Unknown(
                        "blank fabricRunId bound to studioRunId ${studioRunId.value} " +
                            "(the persisted binding cannot address a Fabric run)",
                    ),
                )
            } else {
                CancellationTarget.Resolved(resolved)
            }
        } catch (failure: Exception) {
            CancellationTarget.NotDispatched(
                FabricCancellationResult.Refused(
                    "binding lookup failed for studioRunId ${studioRunId.value}: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                ),
            )
        }

    /**
     * Put [fabricRunId] on the wire and map Fabric's answer.
     *
     * Only an accepted cancel becomes
     * [FabricCancellationResult.CancelRequested] — an intent. Nothing
     * here synthesises a terminal state; the run's state is read back,
     * never inferred from a cancel.
     */
    private suspend fun dispatchCancel(fabricRunId: FabricRunId): FabricCancellationResult {
        val command = CancelRunCommand(
            runId = fabricRunId,
            reason = null,
        )
        return try {
            when (val reply = runControl.cancel(command)) {
                is CommandResult.Accepted -> FabricCancellationResult.CancelRequested
                is CommandResult.Rejected -> mapCancellationRejection(reply.reason)
            }
        } catch (failure: FabricTransportFailure) {
            FabricCancellationResult.Refused(
                "transport failure on RunControl.cancel: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricCancellationResult.Refused(
                "unexpected error on RunControl.cancel: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * Map a Fabric-side [Rejection] to a Studio-side
     * [FabricSubmissionResult].
     *
     * The five arms in [Rejection] map to a `Refused` whose `reason`
     * is a short human-readable string. The "already running" case
     * (a [Rejection.Conflict] with `kind == "start"`) maps to
     * [FabricSubmissionResult.AlreadyRunning] so the HTTP layer can
     * render it as a 200 with the existing run.
     */
    private fun mapRejection(reason: Rejection): FabricSubmissionResult = when (reason) {
        is Rejection.Conflict ->
            if (reason.kind == "start" && reason.detail.contains("already running", ignoreCase = true)) {
                // No way to recover the fabricRunId from a Conflict
                // (it carries kind+id+detail, not a value). The HTTP
                // layer treats AlreadyRunning without a fabricRunId
                // as "the run exists, look it up by studioRunId" —
                // a H2.3 lookup table will provide the binding.
                FabricSubmissionResult.AlreadyRunning(
                    fabricRunId = FabricRunId(""),
                )
            } else {
                FabricSubmissionResult.Refused("conflict: ${reason.detail}")
            }
        is Rejection.NotFound ->
            FabricSubmissionResult.Refused("not found: ${reason.kind} ${reason.id}")
        is Rejection.NotPermitted ->
            FabricSubmissionResult.Refused("not permitted: ${reason.detail}")
        is Rejection.UnsupportedVersion ->
            FabricSubmissionResult.Refused(
                "unsupported version: client=${reason.found}, server=${reason.supported}",
            )
        // SFW-H2.2: the three package-side refusals on the StartRun
        // reply when packageDigest is set. Each carries data the
        // generic Conflict arm cannot, and a Studio caller benefits
        // from seeing it (e.g. for splitting the bundle on
        // PackageTooLarge). The Studio-side rendering is a
        // human-readable reason; the structured fields would be a
        // future refinement if a UI ever wants them.
        is Rejection.PackageTooLarge ->
            FabricSubmissionResult.Refused(
                "package too large: ${reason.sizeBytes} bytes > ${reason.maxBytes} max",
            )
        is Rejection.PackageDigestMismatch ->
            FabricSubmissionResult.Refused(
                "package digest mismatch: declared=${reason.declared}, actual=${reason.actual}",
            )
        is Rejection.AlreadyExistsWithDifferentBytes ->
            FabricSubmissionResult.Refused(
                "package exists with different bytes: requested=${reason.packageDigest}, " +
                    "stored=${reason.existingDigestOfBytes}",
            )
    }

    /**
     * Map a Fabric-side [Rejection] for cancel to a Studio-side
     * [FabricCancellationResult].
     *
     * The distinction the cancel call needs to honour: a
     * [Rejection.Conflict] with `kind == "cancel"` and detail
     * mentioning a terminal state is
     * [FabricCancellationResult.AlreadyTerminal], not a generic
     * `Refused`; a [Rejection.NotFound] is [FabricCancellationResult.Unknown]
     * (Fabric does not know about the run, which is a different fact
     * from "we tried to cancel and Fabric refused on grounds we do
     * not understand").
     */
    private fun mapCancellationRejection(
        reason: Rejection,
    ): FabricCancellationResult = when (reason) {
        is Rejection.Conflict ->
            if (reason.detail.contains("terminal", ignoreCase = true) ||
                reason.detail.contains("finished", ignoreCase = true) ||
                reason.detail.contains("cancelled", ignoreCase = true)
            ) {
                FabricCancellationResult.AlreadyTerminal(reason.detail)
            } else {
                FabricCancellationResult.Refused("conflict: ${reason.detail}")
            }
        is Rejection.NotFound ->
            FabricCancellationResult.Unknown("not found: ${reason.kind} ${reason.id}")
        is Rejection.NotPermitted ->
            FabricCancellationResult.Refused("not permitted: ${reason.detail}")
        is Rejection.UnsupportedVersion ->
            FabricCancellationResult.Refused(
                "unsupported version: client=${reason.found}, server=${reason.supported}",
            )
        // The three H2.2 package-side refusals never reach a cancel
        // (cancel takes a runId, not a packageDigest), but the when
        // expression must be exhaustive over the closed Rejection
        // family. Map them to a generic Refused; if a future
        // protocol version makes one reachable on cancel, the
        // mapping can be refined.
        is Rejection.PackageTooLarge ->
            FabricCancellationResult.Refused(
                "package too large on cancel (unexpected): ${reason.sizeBytes} bytes",
            )
        is Rejection.PackageDigestMismatch ->
            FabricCancellationResult.Refused("package digest mismatch on cancel (unexpected)")
        is Rejection.AlreadyExistsWithDifferentBytes ->
            FabricCancellationResult.Refused(
                "package exists with different bytes on cancel (unexpected)",
            )
    }
}

/**
 * The canonical fingerprint of one Fabric start request — the value the
 * [FabricStartIntentStore] persists next to the command identity so a
 * retry of *the same* request is recognised and a *contradiction* is
 * refused (P0-03 / S4).
 *
 * ## What is inside, and what is deliberately outside
 *
 * Every immutable field of the wire request except the command identity
 * itself, plus the Studio-side fields the wire shape is derived from:
 *
 * | field | source |
 * | --- | --- |
 * | `studioRunId` | [RunRequest.runId] (and `StartRunRequest.studioRunId`) |
 * | `studio.snapshotId` | [RunRequest.snapshotId] |
 * | `studio.scenarioId` | [RunRequest.scenarioId] |
 * | `studio.profile` | [RunRequest.profile] |
 * | `wire.packageDigest` | the registry's answer |
 * | `wire.pipelineKey` | derived from the digest |
 * | `wire.displayName` | derived from the Studio id |
 * | `wire.parameters` | the closed parameter set, in its canonical order |
 * | `wire.correlation` | `null` today, included so it cannot drift silently |
 *
 * The `commandId` is excluded on purpose: it **is** the identity being
 * reserved, and folding it in would make every retry look like a
 * contradiction.
 *
 * The volatile values are excluded too: no clock, no random id, no
 * `hashCode`, no `toString` of a map or set. Nothing here can differ
 * between two calls with the same content, which is what makes the
 * fingerprint usable as a durable equality test.
 *
 * ## Why length-prefixed fields instead of a `|` join
 *
 * `a|b` + `c` and `a` + `b|c` are the same string under a delimiter
 * join; the request that produced them is not the same request (the
 * `DedupRunUseCaseImpl` key has the same weakness for ids that contain
 * a separator). Each field is written as `name=<char-count>:<chars>`,
 * so the encoding is self-delimiting: no value can move a boundary,
 * whatever punctuation it contains. The digest is SHA-256 over the
 * UTF-8 bytes of that canonical text, spelled as lowercase hex — the
 * `DedupRunUseCaseImpl` convention, which
 * [FabricStartIntentFingerprint.canonicalize] rewrites to
 * `sha256:<hex>` before it is stored.
 */
internal object FabricStartRequestFingerprint {

    fun of(request: RunRequest, packageDigest: String): String {
        val canonical = StringBuilder()
        field(canonical, "studioRunId", request.runId.value)
        field(canonical, "studio.snapshotId", request.snapshotId.value)
        field(canonical, "studio.scenarioId", request.scenarioId.value)
        field(canonical, "studio.profile", profileName(request.profile))
        field(canonical, "wire.packageDigest", packageDigest)
        field(canonical, "wire.pipelineKey", pipelineKeyOf(packageDigest))
        field(canonical, "wire.displayName", displayNameOf(request.runId))
        appendParameters(canonical, RunParameters.EMPTY)
        // The adapter sends no correlation today; naming the absence
        // keeps "null" from being confusable with "absent field".
        field(canonical, "wire.correlation", null)
        return sha256Hex(canonical.toString())
    }

    /**
     * The [ExecutionProfile] as a stable name. An exhaustive `when` over
     * the sealed family, not a reflective class name: renaming a case
     * has to be a deliberate edit here, not a silent fingerprint change
     * (or a silent collision, which is worse).
     */
    private fun profileName(profile: ExecutionProfile): String = when (profile) {
        is ExecutionProfile.TrustedLocal -> "trusted-local"
        is ExecutionProfile.Untrusted -> "untrusted"
    }

    /**
     * The parameter set in its canonical (sorted, duplicate-free) order.
     * [RunParameters] guarantees that order on construction, so no set or
     * map iteration order can reach the digest.
     */
    private fun appendParameters(out: StringBuilder, parameters: RunParameters) {
        field(out, "wire.parameters.count", parameters.entries.size.toString())
        parameters.entries.forEachIndexed { index, parameter ->
            val prefix = "wire.parameters[$index]"
            field(out, "$prefix.name", parameter.name)
            when (parameter) {
                is RunParameter.Text -> field(out, "$prefix.text", parameter.value)
                is RunParameter.Choice -> {
                    field(out, "$prefix.choice", parameter.value)
                    field(out, "$prefix.choices.count", parameter.choices.size.toString())
                    parameter.choices.forEachIndexed { choice, value ->
                        field(out, "$prefix.choices[$choice]", value)
                    }
                }
                is RunParameter.Flag -> field(out, "$prefix.flag", parameter.enabled.toString())
                is RunParameter.Credential ->
                    field(out, "$prefix.credentialRef", parameter.secret.value)
            }
        }
    }

    /**
     * One self-delimiting `name=<chars>:<value>` line. `null` and the
     * empty string are different bytes, so a field that gains a value
     * can never look like one that had none.
     */
    private fun field(out: StringBuilder, name: String, value: String?) {
        out.append(name).append('=')
        if (value == null) {
            out.append("null")
        } else {
            out.append(value.length).append(':').append(value)
        }
        out.append('\n')
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
