package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.OutputCursor
import dev.pipelinek.studio.domain.RunId

/**
 * Honest stubs for S0 bootstrap. Real implementations land in S1 (SSE + paginated output)
 * and S2 (event journal). Per "no fake PASS" rule, these return explicit refusal — never
 * empty pages or zeroed sequences.
 */
object RefusingRunReadPort : RunReadPort {
    override fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult =
        RunReadResult.Unknown("S0 bootstrap: RunReadPort.events not yet implemented; lands in S1")

    override fun state(runId: RunId): RunReadResult =
        RunReadResult.Unknown("S0 bootstrap: RunReadPort.state not yet implemented; lands in S1")
}

object RefusingOutputReadPort : OutputReadPort {
    override fun read(runId: RunId, cursor: OutputCursor?, byteBudget: Int): OutputReadResult =
        OutputReadResult.Refused("S0 bootstrap: OutputReadPort not yet implemented; lands in S1 — see BLOCKED-BY-OBS until Output Plane certified")
}
