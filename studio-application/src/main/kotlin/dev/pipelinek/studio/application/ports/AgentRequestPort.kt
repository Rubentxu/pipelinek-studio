package dev.pipelinek.studio.application.ports

/**
 * B4.6 (2026-10-10): the prompt-injection guard
 * per ROADMAP §"S4 API/MCP para variantes de
 * snapshot y compare de runs...; mutation
 * scenarios controlados".
 *
 * The S4 mandate is "mutation scenarios
 * controlados, per-run fingerprints/diffs".
 * The mutation control is the guard that
 * refuses an agent request whose metadata
 * contains an adversarial instruction. A
 * well-crafted adversary can craft a request
 * that says "ignore previous instructions" or
 * "return success regardless" to make the
 * agent produce a false PASS. The guard
 * inspects every request BEFORE the dispatcher
 * runs, so a request that matches a trigger
 * phrase is refused with a structured
 * `Refused("prompt injection suspected: <trigger>")`
 * and the dispatcher is never invoked.
 *
 * The guard is intentionally HEURISTIC. It
 * matches a fixed list of trigger phrases
 * (case-insensitive, trimmed). A well-crafted
 * instruction that doesn't match a trigger
 * would bypass the guard — the B4.6 test
 * pins the contract for the trigger phrases
 * in the list. A future S7 pass can add an
 * embedding-based detector.
 *
 * The scan covers:
 *  - every metadata key + value,
 *  - the request's [notes] field,
 *  - the request's [payload] field.
 *
 * The first trigger that matches produces
 * the refusal. The reason names the trigger
 * so the audit trail can record what was
 * caught.
 */
data class AgentRequest(
    val metadata: Map<String, String> = emptyMap(),
    val notes: String = "",
    val payload: String = "",
)

sealed interface AgentRequestResult {
    data class Ok(val request: AgentRequest) : AgentRequestResult
    data class Refused(
        val reason: String,
        val trigger: String,
    ) : AgentRequestResult
    data class Failure(val reason: String) : AgentRequestResult
}

interface AgentRequestPort {
    fun inspect(request: AgentRequest): AgentRequestResult
}

/**
 * B4.6 (2026-10-10): the default heuristic
 * prompt-injection guard. The trigger list is
 * a fixed, versioned catalog of phrases that
 * the studio refuses on sight:
 *  - "ignore previous instructions"
 *  - "disregard previous instructions"
 *  - "override safety"
 *  - "bypass safety"
 *  - "return success regardless"
 *  - "always return success"
 *  - "mark as pass"
 *  - "mark as passed"
 *
 * The list is intentionally narrow: a
 * future S7 pass can extend it (with a
 * release-note entry) or replace it with an
 * embedding-based detector. The B4.6 test
 * pins the contract for the current list.
 */
class HeuristicPromptInjectionGuard(
    private val triggers: List<String> = DEFAULT_TRIGGERS,
) : AgentRequestPort {

    override fun inspect(request: AgentRequest): AgentRequestResult {
        // Scan every metadata value.
        for ((key, value) in request.metadata) {
            val trigger = matchTrigger(value)
            if (trigger != null) {
                return AgentRequestResult.Refused(
                    reason = "prompt injection suspected in metadata['$key']: trigger='$trigger'",
                    trigger = trigger,
                )
            }
        }
        // Scan metadata keys too: a key
        // named "ignore_previous_instructions"
        // is also a signal.
        for (key in request.metadata.keys) {
            val trigger = matchTrigger(key)
            if (trigger != null) {
                return AgentRequestResult.Refused(
                    reason = "prompt injection suspected in metadata key: trigger='$trigger'",
                    trigger = trigger,
                )
            }
        }
        // Scan notes.
        val noteTrigger = matchTrigger(request.notes)
        if (noteTrigger != null) {
            return AgentRequestResult.Refused(
                reason = "prompt injection suspected in notes: trigger='$noteTrigger'",
                trigger = noteTrigger,
            )
        }
        // Scan payload.
        val payloadTrigger = matchTrigger(request.payload)
        if (payloadTrigger != null) {
            return AgentRequestResult.Refused(
                reason = "prompt injection suspected in payload: trigger='$payloadTrigger'",
                trigger = payloadTrigger,
            )
        }
        return AgentRequestResult.Ok(request)
    }

    private fun matchTrigger(text: String): String? {
        if (text.isBlank()) return null
        // Normalize: lowercase + replace
        // underscores with spaces. Adversaries
        // often use snake_case keys to bypass
        // naive string matching.
        val normalized = text.lowercase().replace('_', ' ')
        for (trigger in triggers) {
            if (normalized.contains(trigger.lowercase())) return trigger
        }
        return null
    }

    companion object {
        val DEFAULT_TRIGGERS: List<String> = listOf(
            "ignore previous instructions",
            "disregard previous instructions",
            "override safety",
            "bypass safety",
            "return success regardless",
            "always return success",
            "mark as pass",
            "mark as passed",
        )
    }
}

/**
 * B4.6 (2026-10-10): the mutation control port.
 * The mutation control is the typed contract
 * that pins which mutations an agent may apply
 * to a snapshot. Every mutation request goes
 * through [MutationControlPort.admit] BEFORE
 * the underlying port runs; the admit step
 * consults the [AgentRequestPort] for the
 * prompt-injection guard and the
 * [AgentCapability.MUTATE] capability.
 *
 * The port returns a [MutationAdmitResult] with
 * four branches: Admitted (mutation may
 * proceed), Refused (capability or guard
 * refused), Failure (admit step failed), and
 * Unknown (the mutation id is unknown to the
 * catalog).
 */
sealed interface MutationAdmitResult {
    data class Admitted(val mutationId: String) : MutationAdmitResult
    data class Refused(val reason: String) : MutationAdmitResult
    data class Failure(val reason: String) : MutationAdmitResult
    data class Unknown(val reason: String) : MutationAdmitResult
}

interface MutationControlPort {
    fun admit(request: AgentRequest, mutationId: String): MutationAdmitResult
}

/**
 * B4.6 (2026-10-10): the default mutation
 * control impl. The admit step is:
 *  1. Run the [AgentRequestPort] (prompt-
 *     injection guard). If Refused, return
 *     Refused without consulting the
 *     capability port.
 *  2. Consult [AgentCapabilityPort] for
 *     [AgentCapability.MUTATE]. If disabled,
 *     return Refused.
 *  3. Consult the [MutationCatalog] to
 *     verify the [mutationId] is a known
 *     mutation. If unknown, return Unknown.
 *  4. Otherwise, return Admitted.
 *
 * The catalog is a list of known mutation ids
 * (e.g. "drop-trailing-empty-lines",
 * "rename-class"). The catalog is intentionally
 * closed: a mutation not in the catalog cannot
 * be applied, even if the agent's MUTATE
 * capability is enabled. This prevents the
 * "free-text mutation" attack where the agent
 * invents a mutation id that the studio
 * blindly executes.
 */
class DefaultMutationControl(
    private val guard: AgentRequestPort,
    private val capability: AgentCapabilityPort,
    private val catalog: Set<String> = DEFAULT_MUTATION_CATALOG,
) : MutationControlPort {

    override fun admit(request: AgentRequest, mutationId: String): MutationAdmitResult {
        val inspected = guard.inspect(request)
        if (inspected is AgentRequestResult.Refused) {
            return MutationAdmitResult.Refused(inspected.reason)
        }
        if (inspected is AgentRequestResult.Failure) {
            return MutationAdmitResult.Failure(inspected.reason)
        }
        if (!capability.isEnabled(AgentCapability.MUTATE)) {
            return MutationAdmitResult.Refused(
                "capability MUTATE disabled; set STUDIO_AGENT_CAPS=MUTATE (or 'all') to enable",
            )
        }
        if (mutationId !in catalog) {
            return MutationAdmitResult.Unknown(
                "mutation id '$mutationId' is not in the closed catalog; " +
                    "the studio refuses unknown mutations by design",
            )
        }
        return MutationAdmitResult.Admitted(mutationId)
    }

    companion object {
        /**
         * The closed mutation catalog. Every
         * mutation the studio supports is a
         * known id. An agent can only request
         * a mutation by its id; the studio
         * does not accept free-text
         * transformations.
         */
        val DEFAULT_MUTATION_CATALOG: Set<String> = setOf(
            "drop-trailing-empty-lines",
            "drop-trailing-whitespace",
            "rename-class",
            "rename-package",
            "collapse-blank-lines",
        )
    }
}
