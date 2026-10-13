@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadRequest
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadResult
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionSource
import io.jenkins.pipelinek.fabric.contracts.observation.RunIndex
import io.jenkins.pipelinek.fabric.contracts.observation.RunReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult

/**
 * The second port of the H2.2c contract: a [FabricRunReadPort]
 * that answers over Fabric's [RunIndex] + [ProjectionSource].
 *
 * ## What the adapter does, in one paragraph
 *
 * A Studio caller hands the adapter a [RunId] (the studio-side
 * identity it minted before submit). The adapter
 *
 *  1. resolves the [RunId] to a Fabric-side [FabricRunId] via the
 *     injected [BindingLookup] (a `null` answer is the adapter's
 *     own [FabricRunSummaryResult.Unknown] / [FabricEventsResult.Unknown]
 *     and no Fabric call is made);
 *  2. for `summary`, calls
 *     [RunIndex.summaryOf] and maps the result (see [mapSummary]);
 *  3. for `events`, builds a [FactReadRequest] with the
 *     [FabricRunId] and the caller's [FactCursor] + limit, calls
 *     [ProjectionSource.readAfter], and maps the result (see
 *     [mapEvents]);
 *  4. catches [FabricTransportFailure] from either port and
 *     renders it as a `Refused` (the carve-out asserted in
 *     H2.2c, applied to the read side too).
 *
 * The reason for the wrapper result types is in
 * [FabricRunReadPort] KDoc; the short version is that a Studio
 * that returned Fabric's `RunSummaryResult` / `FactReadResult`
 * to the HTTP layer would put `:contracts:observation` on the
 * response surface, which is a contract leak.
 *
 * ## Why the [BindingLookup] is a constructor parameter
 *
 * Same pattern as [FabricRunnerAdapter]'s [PackageRegistry]:
 * the seam is declared here as a function; H2.3 backs it with
 * the SQLite table `studio.run_bindings`. A unit test passes
 * an in-memory map; production wires the SQLite-backed
 * implementation.
 *
 * @param runIndex Fabric's [RunIndex] (a [io.jenkins.pipelinek.fabric.control.client.FabricClient]
 *   in production, a fake in unit tests). Required.
 * @param projectionSource Fabric's [ProjectionSource] (the
 *   same [io.jenkins.pipelinek.fabric.control.client.FabricClient]
 *   in production, a fake in unit tests). Required.
 * @param bindingLookup the [RunId] → [FabricRunId] resolver.
 *   Required — a null return is the adapter's own `Unknown`.
 */
class FabricRunReadAdapter(
    private val runIndex: RunIndex,
    private val projectionSource: ProjectionSource,
    private val bindingLookup: BindingLookup,
) : FabricRunReadPort {

    override suspend fun summary(studioRunId: RunId): FabricRunSummaryResult {
        val fabricRunId: FabricRunId = bindingLookup.fabricRunIdFor(studioRunId)
            ?: return FabricRunSummaryResult.Unknown(
                "no binding for studioRunId ${studioRunId.value} (the run was never submitted, " +
                    "or it was submitted to a different Fabric server)",
            )
        return try {
            when (val reply = runIndex.summaryOf(fabricRunId)) {
                is RunSummaryResult.Found -> FabricRunSummaryResult.Found(reply.summary)
                is RunSummaryResult.Refused -> when (val r = reply.refusal) {
                    is RunReadRefusal.UnknownRun ->
                        FabricRunSummaryResult.Unknown("unknown run: ${r.runId.value}")
                    is RunReadRefusal.UnobservedRun ->
                        FabricRunSummaryResult.Unknown(
                            "unobserved run: ${r.runId.value} (admitted but not yet observed)",
                        )
                    is RunReadRefusal.IncompleteObservation ->
                        FabricRunSummaryResult.Refused("incomplete observation: ${r.because}")
                    is RunReadRefusal.ReadBudgetExhausted ->
                        FabricRunSummaryResult.Refused(
                            "read budget exhausted (${r.reads} reads); " +
                                "retry with a larger page or after a smaller read",
                        )
                    is RunReadRefusal.NonPositivePageSize ->
                        FabricRunSummaryResult.Refused("non-positive page size: ${r.pageSize}")
                    is RunReadRefusal.UnknownCursor ->
                        FabricRunSummaryResult.Refused(
                            "unknown cursor: ${r.cursor.lastRunId.value}@${r.cursor.offset}",
                        )
                    is RunReadRefusal.CursorScopeMismatch ->
                        FabricRunSummaryResult.Refused("cursor scope mismatch")
                    is RunReadRefusal.CursorOutOfScope ->
                        FabricRunSummaryResult.Refused(
                            "cursor out of scope: ${r.cursor.lastRunId.value} " +
                                "in scope pipelineKey=${r.scope.pipelineKey}",
                        )
                }
            }
        } catch (failure: FabricTransportFailure) {
            // The seam (asserted in H2.2c, applied here too):
            // a transport failure is a caller-visible Refused,
            // not Unknown.
            FabricRunSummaryResult.Refused(
                "transport failure on RunIndex.summaryOf: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricRunSummaryResult.Refused(
                "unexpected error on RunIndex.summaryOf: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    override suspend fun events(
        studioRunId: RunId,
        after: FactCursor,
        limit: Int,
    ): FabricEventsResult {
        val fabricRunId: FabricRunId = bindingLookup.fabricRunIdFor(studioRunId)
            ?: return FabricEventsResult.Unknown(
                "no binding for studioRunId ${studioRunId.value} (the run was never submitted, " +
                    "or it was submitted to a different Fabric server)",
            )
        val request = FactReadRequest(
            runId = fabricRunId,
            after = after,
            limit = limit,
        )
        return try {
            when (val reply = projectionSource.readAfter(request)) {
                is FactReadResult.Page -> FabricEventsResult.Page(
                    facts = reply.page.facts,
                    next = reply.page.next,
                    complete = reply.page.complete,
                    version = reply.page.version,
                )
                is FactReadResult.Refused -> when (val r = reply.refusal) {
                    is io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal.UnknownRun ->
                        FabricEventsResult.Unknown("unknown run: ${r.runId.value}")
                    is io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal.CursorBeforeStart ->
                        FabricEventsResult.Refused(
                            "cursor before start: after=${r.after.sequence} " +
                                "< firstSequence=${r.firstSequence}",
                        )
                    is io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal.NonPositiveLimit ->
                        FabricEventsResult.Refused("non-positive limit: ${r.limit}")
                    is io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal.UnmappedEvent ->
                        FabricEventsResult.Refused(
                            "unmapped event: kind=${r.kind} at after=${r.after.sequence} " +
                                "(the build cannot translate this kind)",
                        )
                    is io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal.EventVersionTooNew ->
                        FabricEventsResult.Refused(
                            "event version too new: eventVersion=${r.eventVersion} " +
                                "at after=${r.after.sequence} (kind=${r.kind}); " +
                                "this build does not know that version",
                        )
                }
            }
        } catch (failure: FabricTransportFailure) {
            FabricEventsResult.Refused(
                "transport failure on ProjectionSource.readAfter: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricEventsResult.Refused(
                "unexpected error on ProjectionSource.readAfter: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }
}
