package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.UpstreamRunId

/**
 * Resolves a Studio [RunId] to the upstream execution identity it was
 * admitted as, or answers `null` when the Studio has no usable record
 * of that run against the upstream.
 *
 * ## Why the port lives in the application layer
 *
 * The fact it answers lives in the Studio's own `run_bindings` row, and
 * nothing about that row belongs to a particular remote backend: the
 * persistence is a Studio artifact. So the port sits next to the other
 * application ports and the *upstream* module — not the storage layer —
 * owns the adapter that turns the answer into its own wire identity
 * (`BindingLookup.from` in `studio-infra-fabric`). That is what keeps
 * the storage layer compilable and testable with every remote backend
 * (and its contract artifacts) absent.
 *
 * ## Why [UpstreamRunId] is imported from the domain, not declared here
 *
 * The domain already owns the concept
 * ([dev.pipelinek.studio.domain.RunBinding.upstreamRunId]) and its one
 * rule (`isNotBlank`). A second value class with the same name would be
 * a second identity rule for the same fact. Deliberately no UUID/format
 * gate: the persisted value is an opaque id minted upstream and is not
 * guaranteed to be a UUID, so any format check here would reject real
 * rows (and real fixtures) the store has always accepted.
 */
fun interface UpstreamRunIdLookup {

    /**
     * The upstream identity [studioRunId] was admitted as, or `null`
     * when the binding-lookup missed — no binding row, or a row whose
     * upstream id is unusable.
     *
     * An implementation must never answer a blank identity. "There is
     * a binding but its id addresses nothing" and "there is no binding"
     * are the same fact to every caller (both refuse to dispatch, and
     * neither may fall back to the Studio id), while a blank id would
     * otherwise travel one step further — into a cancellation call.
     */
    fun upstreamRunIdFor(studioRunId: RunId): UpstreamRunId?
}
