package dev.pipelinek.studio.application.ports

import java.time.Instant

/**
 * R4.3 item 3 (2026-10-11): audit log of every action the studio
 * executes in response to an MCP/HTTP/agent call. Per the S4 mandate:
 * 'Registrar actor, autorización, decisión y efecto real sin persistir
 * secretos'.
 *
 * The audit log is the third line of defense (after the capability
 * gate and the prompt-injection guard). Every dispatch lands here,
 * both allowed AND refused, so a post-hoc review can answer:
 *  - Who did what? (`actor`)
 *  - Was the action authorized? (`decision` + `capability`)
 *  - Did it actually produce an effect? (`effectRef`)
 *
 * Persistence rule: the entry MUST NOT carry secrets. Idempotency
 * keys, tokens, request bodies, or stack traces are out of scope.
 * Only identifying metadata that does not leak credentials.
 */
data class AuditEntry(
    val actor: String,
    val capability: AgentCapability,
    val action: String,
    val decision: AuditDecision,
    /**
     * Opaque identifier of the produced effect (a snapshot id, a run
     * id, a comparison id). NULL when the decision was REFUSED (no
     * effect was produced) or when the action does not produce a
     * durable effect.
     */
    val effectRef: String? = null,
    val timestamp: Instant,
)

enum class AuditDecision { ALLOWED, REFUSED }

/**
 * R4.3 item 3: read-only port to record audit entries. The producer
 * implementation (audit log adapter) is wired in the production
 * wiring layer. For now the contract is declared so the dispatcher
 * can call it on every dispatch decision.
 */
interface AuditLogPort {
    fun record(entry: AuditEntry): Unit
}