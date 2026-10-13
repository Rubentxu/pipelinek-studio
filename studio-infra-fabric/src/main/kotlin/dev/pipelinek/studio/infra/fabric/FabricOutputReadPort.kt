@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.ConsoleRef
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId

/**
 * The Studio-side port for a remote console, answered by a Fabric
 * server over gRPC.
 *
 * ## Why this is a sibling of [dev.pipelinek.studio.application.ports.OutputReadPort],
 *    not a replacement
 *
 * The existing local port returns [dev.pipelinek.studio.application.ports.OutputChunk]
 * with a `streamId: String` and an `OutputCursor`. The remote port
 * carries the wire's `ConsoleRef` (a `value class` over `String`)
 * and arithmetic byte offsets. The two shapes line up on the byte
 * stream but differ on the cursor type: a `ConsoleRef` is opaque
 * (the wire mints it; a Studio caller holds it from a previous
 * fact stream read), while the local `streamId` is a Studio-minted
 * identifier.
 *
 * `FabricOutputReadPort` keeps the two surfaces separate, same as
 * the read and write ports do. A thin adapter from one shape to
 * the other is a H2.3 work-unit; the H2.2c+2 port is honest
 * about the wire shape.
 */
interface FabricOutputReadPort {

    /**
     * Read at most [maxBytes] bytes of one console, after
     * [fromByteOffset].
     *
     * [console] is a [ConsoleRef] the caller holds from a previous
     * fact stream read (typically a `RunStarted` event's console
     * ref). The adapter does not mint refs; forwarding one the
     * caller did not earn is the failure mode the
     * [io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRefusal.ForeignConsole]
     * arm exists to catch.
     *
     * @return [FabricOutputReadResult.Page] with the bytes and the
     *   next cursor; [FabricOutputReadResult.Unknown] when the
     *   binding-lookup missed OR Fabric has no such console;
     *   [FabricOutputReadResult.Refused] for any other refusal
     *   (the carve-out: transport failure is a refusal, not
     *   Unknown).
     */
    suspend fun read(
        studioRunId: RunId,
        console: ConsoleRef,
        fromByteOffset: Long,
        maxBytes: Int,
    ): FabricOutputReadResult
}

/**
 * The result of [FabricOutputReadPort.read], remote-shaped.
 *
 * Three arms:
 * - [Page] carries the Fabric `ConsolePage` shape (`console`,
 *   `fromByteOffset`, `nextByteOffset`, `text`,
 *   `atEndOfStream`) — those fields are simple data and worth
 *   carrying as-is;
 * - [Unknown] is a Studio addition covering two real cases:
 *   the binding-lookup missed (Studio-side gap) and Fabric's
 *   [io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRefusal.UnknownConsole]
 *   refusal (the run is here, the console handle isn't);
 * - [Refused] is everything else — the other 12 arms of the
 *   [io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRefusal]
 *   family, plus the [FabricTransportFailure] carve-out.
 *
 * Distinct from [io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadResult]
 * because the `Unknown` arm is a Studio addition and the seam
 * is meant for the HTTP layer to render without depending on
 * Fabric's types.
 */
sealed interface FabricOutputReadResult {
    data class Page(
        val console: ConsoleRef,
        val fromByteOffset: Long,
        val nextByteOffset: Long,
        val text: String,
        val atEndOfStream: Boolean,
    ) : FabricOutputReadResult
    data class Unknown(val reason: String) : FabricOutputReadResult
    data class Refused(val reason: String) : FabricOutputReadResult
}
