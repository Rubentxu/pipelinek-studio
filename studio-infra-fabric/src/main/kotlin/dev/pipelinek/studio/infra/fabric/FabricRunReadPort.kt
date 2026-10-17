@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.UpstreamRunIdLookup
import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact

/**
 * The Studio-side read port for a remote run, answered by a Fabric
 * server over gRPC.
 *
 * ## Why this is a sibling of [dev.pipelinek.studio.application.ports.RunReadPort],
 *    not a replacement
 *
 * The existing local port returns events in terms of a
 * `Long`-sequence [dev.pipelinek.studio.application.ports.EventCursor]
 * and a flat `List<RunEvent>`. Fabric's read side uses
 * [FactCursor] (which happens to be a `Long`-sequence too — the
 * mapping is identity) and a [ProjectionFact] hierarchy (10
 * sealed variants). The two shapes line up on the cursor but
 * differ on the fact type, and forcing a single port shape would
 * either collapse the [ProjectionFact] variants to a generic
 * payload (losing the closed family that makes a reducer
 * exhaustive) or pull Fabric's contract into the Studio's
 * response surface.
 *
 * `FabricRunReadPort` keeps the two surfaces separate, same as
 * the write port [FabricRunnerPort] does.
 *
 * ## Why two methods, not one with an enum
 *
 * `summary` is a single-shot question ("what is this run?"); its
 * failures are about **observation** (nobody has observed it, the
 * read stopped short, Fabric declined to spend the budget).
 * `events` is a streamed question ("give me the next page"); its
 * failures are about **position** (a cursor from another question,
 * a page size of zero). The two are not the same shape, and a
 * method that asked both would force one of them to give up the
 * refusals it needs. This is the same argument
 * [io.jenkins.pipelinek.fabric.contracts.observation.RunIndex]
 * makes against a single "find a run" method, and the answer is
 * the same: two methods, each with the refusals it needs.
 */
interface FabricRunReadPort {

    /**
     * One run's summary, or a typed reason there is none.
     *
     * @return [FabricRunSummaryResult.Found] with the [RunSummary]
     *   when Fabric answered; [FabricRunSummaryResult.Unknown]
     *   when the binding-lookup missed OR Fabric does not hold
     *   the run; [FabricRunSummaryResult.Refused] for any other
     *   refusal (the H2 carve-out: transport failure is a refusal,
     *   not Unknown).
     */
    suspend fun summary(studioRunId: RunId): FabricRunSummaryResult

    /**
     * One page of the fact stream, or a typed reason there is none.
     *
     * [after] is the [FactCursor] the caller has consumed so far
     * ([FactCursor.NONE] for the first page); [limit] is a request,
     * not a guarantee. A [FabricEventsResult.Page] may be empty
     * ("the run exists and has produced nothing after this cursor")
     * — that is the load-bearing distinction that lets a Studio
     * tell "this run has produced nothing" from "Fabric has no such
     * run" (KL-008).
     */
    suspend fun events(
        studioRunId: RunId,
        after: FactCursor,
        limit: Int,
    ): FabricEventsResult
}

/**
 * The result of [FabricRunReadPort.summary], remote-shaped.
 *
 * Three arms, one of them a Studio addition over Fabric's two:
 * - [Found] carries the [RunSummary] Fabric returned;
 * - [Unknown] is a Studio addition covering two real cases that
 *   Fabric distinguishes: "the binding-lookup missed" (the
 *   studio run id is not in `studio.run_bindings` — a
 *   Studio-side gap) and "Fabric does not hold the run" /
 *   "the run has not been observed yet" (the [RunReadRefusal.UnknownRun]
 *   / [RunReadRefusal.UnobservedRun] arms);
 * - [Refused] is everything else, including
 *   [RunReadRefusal.IncompleteObservation] and
 *   [RunReadRefusal.ReadBudgetExhausted], plus the
 *   [FabricTransportFailure] carve-out.
 *
 * Distinct from [io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult]
 * because (a) the `Unknown` arm is a Studio addition and (b) the
 * seam is meant for the HTTP layer to render without depending on
 * Fabric's types.
 */
sealed interface FabricRunSummaryResult {
    data class Found(val summary: RunSummary) : FabricRunSummaryResult
    data class Unknown(val reason: String) : FabricRunSummaryResult
    data class Refused(val reason: String) : FabricRunSummaryResult
}

/**
 * The result of [FabricRunReadPort.events], remote-shaped.
 *
 * Three arms, mirroring [FabricRunSummaryResult]:
 * - [Page] carries the page's [facts], the [next] cursor, the
 *   [complete] flag (true when Fabric has reached the end of the
 *   stream and knows there is no more), and the schema [version];
 * - [Unknown] is a Studio addition covering "the binding-lookup
 *   missed" and Fabric's "unknown run" / "cursor before start" /
 *   "non-positive limit" refusals;
 * - [Refused] is everything else (including
 *   [FactReadRefusal.UnmappedEvent] /
 *   [FactReadRefusal.EventVersionTooNew] /
 *   [FactReadRefusal.CursorBeforeStart] / `NonPositiveLimit` that
 *   are not "Unknown" in the Studio sense, plus the transport
 *   carve-out).
 */
sealed interface FabricEventsResult {
    data class Page(
        val facts: List<ProjectionFact>,
        val next: FactCursor,
        val complete: Boolean,
        val version: Int,
    ) : FabricEventsResult
    data class Unknown(val reason: String) : FabricEventsResult
    data class Refused(val reason: String) : FabricEventsResult
}

/**
 * Resolves a Studio [RunId] to the Fabric `RunId` it was admitted
 * as. The seam H2.2c+1 declares for the (3-identities, 1 lookup)
 * requirement; H2.3 backs it with the `studio.run_bindings`
 * SQLite table through [from].
 *
 * A `null` answer means "the binding-lookup missed" — the Studio
 * has no record of this studio run id on this Fabric server. The
 * adapter answers [FabricRunSummaryResult.Unknown] /
 * [FabricEventsResult.Unknown] without ever calling Fabric.
 *
 * ## Why the persistence does not implement this interface
 *
 * Audit P1-04: the store that owns the `run_bindings` row used to
 * implement this seam, which made `:studio-infra-storage` depend on
 * `:studio-infra-fabric` and therefore on Fabric's unpublished
 * contract jars — every Studio module that persisted a binding
 * inherited that requirement. The stored identity is opaque, so the
 * storage layer now answers the application-layer
 * [UpstreamRunIdLookup] port and the conversion to Fabric's `RunId`
 * happens here, in [from], at the only layer that knows the
 * upstream is Fabric.
 */
fun interface BindingLookup {
    fun fabricRunIdFor(studioRunId: RunId): io.jenkins.pipelinek.fabric.contracts.model.RunId?

    companion object {
        /**
         * Bridges the application-layer [UpstreamRunIdLookup] onto this
         * seam.
         *
         * The `null` pass-through is deliberate: a miss on the Studio
         * side is a miss here, and every consumer of this interface
         * already answers its own "no binding" arm for `null` instead
         * of calling Fabric. The conversion is total in the other
         * direction because [UpstreamRunIdLookup] never answers a blank
         * identity (see its KDoc), so no blank `RunId` can be minted
         * here.
         */
        fun from(lookup: UpstreamRunIdLookup): BindingLookup =
            BindingLookup { studioRunId ->
                lookup.upstreamRunIdFor(studioRunId)?.let {
                    io.jenkins.pipelinek.fabric.contracts.model.RunId(it.value)
                }
            }
    }
}
