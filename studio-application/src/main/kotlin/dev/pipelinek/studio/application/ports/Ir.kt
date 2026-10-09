package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId

/**
 * IR Explorer model per S3 ROADMAP §"E3.3 IR Explorer con etapa/Step/block,
 * provider/contract y fases compiled|admitted|observed, sin duplicar compiler".
 *
 * Studio reads the IR from run.db (operation_journal + events) after the SDK
 * has evaluated the script. We never re-execute the compiler; we project the
 * SDK's persisted record. Phases:
 *   compiled     — Kotlin script compiled successfully (CompilationFinished diagnostic)
 *   admitted     — Steps registered (operation_journal rows)
 *   observed     — runtime evidence (events with kind != CompilationXxx)
 *
 * StepIR carries the contract stub: name, qualified className, capabilities
 * (taken from JAR metadata), input/output schema presence. The schema itself
 * is surfaced as Boolean — we never invent a StepCodec.schema() shape.
 */

data class StepIR(
    val opId: String,
    val name: String,
    val className: String,
    val capabilities: List<String>,
    val inputSchemaPresent: Boolean,
    val outputSchemaPresent: Boolean,
    val phase: IRPhase,
)

data class StageIR(
    val name: String,
    val steps: List<StepIR>,
)

data class IrSnapshot(
    val runId: RunId,
    val stages: List<StageIR>,
    val phasesObserved: Set<IRPhase>,
)

enum class IRPhase { COMPILED, ADMITTED, OBSERVED }

sealed interface IrReadResult {
    data class Ok(val snapshot: IrSnapshot) : IrReadResult
    data class Unknown(val reason: String) : IrReadResult
}

interface IrExplorerReadPort {
    fun snapshot(runId: RunId): IrReadResult
}
