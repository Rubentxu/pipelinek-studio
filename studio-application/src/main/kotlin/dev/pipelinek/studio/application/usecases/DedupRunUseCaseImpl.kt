package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupRunResult
import dev.pipelinek.studio.application.ports.DedupRunUseCase
import dev.pipelinek.studio.application.ports.RunRequest
import java.security.MessageDigest

/**
 * SFW-H6.1: the implementation of
 * [DedupRunUseCase] that computes the dedup
 * key from a [RunRequest] and asks the
 * [DedupKeyPort] whether a run with the same
 * key already exists.
 *
 * ## The dispatch in one paragraph
 *
 * The `checkDuplicate(request)` method:
 * 1. computes the dedup key from the
 *    [RunRequest] (a SHA-256 of the canonical
 *    request shape: `snapshotId + scenarioId +
 *    profile` joined with `|`);
 * 2. calls
 *    `port.findByDedupKey(dedupKey)` and
 *    maps the 4-arm response to the
 *    [DedupRunResult] family (the mapping is
 *    identity: the shapes are the same by
 *    design);
 * 3. wraps unexpected exceptions in
 *    [DedupRunResult.Refused] (the H6.1
 *    contract: "any unexpected exception on
 *    the dedup path is a refusal, not an
 *    unknown").
 *
 * ## Why a 4-arm `DedupRunResult` (and not 3)
 *
 * The H4.1 4-arm `CancelRunResult` discipline
 * is load-bearing: a transport failure is a
 * refusal, not an unknown. H6.1 mirrors the
 * discipline: a `Refused(reason)` is a
 * transport failure (the port itself failed),
 * a `NewRun` is the success (no existing run;
 * proceed with the submit), a `DedupedRun(
 * studioRunId, state)` is the dedup hit (an
 * existing run with the same dedup key; the
 * new submit is a noop), an `Unknown(reason)`
 * is "the port does not know" (a future H6+
 * race).
 *
 * ## Why the dedup key is a SHA-256 of the canonical request shape
 *
 * The
 * [dev.pipelinek.studio.application.usecases.StartRunUseCase]
 * already has an idempotency mechanism via
 * the `idempotencyKey` parameter. The dedup is
 * a DIFFERENT idempotency: it's based on the
 * CONTENT of the request. The canonical
 * request shape (`snapshotId + scenarioId +
 * profile`) is the minimum information needed
 * to identify a dedup-equivalent request. A
 * SHA-256 of the canonical shape (joined with
 * `|`) is collision-resistant.
 *
 * @param dedupKeyPort the [DedupKeyPort] the
 *   use case delegates to. The production
 *   boot wires a
 *   [dev.pipelinek.studio.infra.storage.DedupKeyStore].
 */
class DedupRunUseCaseImpl(
    private val dedupKeyPort: DedupKeyPort,
) : DedupRunUseCase {

    override suspend fun checkDuplicate(request: RunRequest): DedupRunResult = try {
        val dedupKey = computeDedupKey(request)
        when (val found = dedupKeyPort.findByDedupKey(dedupKey)) {
            null -> DedupRunResult.NewRun
            else -> DedupRunResult.DedupedRun(
                studioRunId = found.studioRunId,
                state = found.state,
            )
        }
    } catch (failure: Exception) {
        // The H6.1 contract: any unexpected
        // exception on the dedup path is a
        // refusal, not an unknown. The future
        // H6.2+ integration renders this as
        // `503 DEDUP_REFUSED`.
        DedupRunResult.Refused(
            reason = "dedup check failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }

    companion object {
        /**
         * SFW-H6.1: the canonical dedup key
         * computation. SHA-256 of the canonical
         * request shape: `snapshotId + scenarioId
         * + profile` joined with `|`. The
         * `runId` is intentionally excluded
         * (the `runId` is a unique identifier,
         * not a content field).
         */
        fun computeDedupKey(request: RunRequest): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(request.snapshotId.value.toByteArray(Charsets.UTF_8))
            md.update(0)  // NUL separator
            md.update(request.scenarioId.value.toByteArray(Charsets.UTF_8))
            md.update(0)  // NUL separator
            md.update(request.profile::class.simpleName?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
