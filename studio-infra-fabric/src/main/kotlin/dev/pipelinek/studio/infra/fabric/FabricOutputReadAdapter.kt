@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.ConsoleRef
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRequest
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadResult
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleSource

/**
 * The third port of the H2.2c contract: a [FabricOutputReadPort]
 * that answers over Fabric's [ConsoleSource].
 *
 * ## What the adapter does, in one paragraph
 *
 * A Studio caller hands the adapter a [RunId] (its own
 * identity), a [ConsoleRef] (held from a previous fact stream
 * read), a byte offset, and a max bytes. The adapter
 *
 *  1. resolves the [RunId] to a Fabric-side [FabricRunId] via
 *     the injected [BindingLookup] (a `null` answer is the
 *     adapter's own [FabricOutputReadResult.Unknown] and no
 *     Fabric call is made);
 *  2. builds a [ConsoleReadRequest] with the [FabricRunId] +
 *     [ConsoleRef] + the caller's byte offset + max bytes;
 *  3. calls [ConsoleSource.read] and translates the result
 *     (see the `when` over [ConsoleReadResult]);
 *  4. catches [FabricTransportFailure] and renders it as a
 *     `Refused` (the carve-out, applied here too).
 *
 * ## Why the [BindingLookup] is a constructor parameter
 *
 * Same pattern as [FabricRunnerAdapter]'s [PackageRegistry] and
 * [FabricRunReadAdapter]'s [BindingLookup]: the seam is
 * declared here as a function; H2.3 backs it with the SQLite
 * table `studio.run_bindings`. A unit test passes an
 * in-memory map; production wires the SQLite-backed
 * implementation.
 *
 * @param consoleSource Fabric's [ConsoleSource] (a
 *   [io.jenkins.pipelinek.fabric.control.client.FabricClient] in
 *   production, a fake in unit tests). Required.
 * @param bindingLookup the [RunId] → [FabricRunId] resolver.
 *   Required — a null return is the adapter's own `Unknown`.
 */
class FabricOutputReadAdapter(
    private val consoleSource: ConsoleSource,
    private val bindingLookup: BindingLookup,
) : FabricOutputReadPort {

    override suspend fun read(
        studioRunId: RunId,
        console: ConsoleRef,
        fromByteOffset: Long,
        maxBytes: Int,
    ): FabricOutputReadResult {
        val fabricRunId: FabricRunId = bindingLookup.fabricRunIdFor(studioRunId)
            ?: return FabricOutputReadResult.Unknown(
                "no binding for studioRunId ${studioRunId.value} (the run was never submitted, " +
                    "or it was submitted to a different Fabric server)",
            )
        val request = ConsoleReadRequest(
            runId = fabricRunId,
            console = console,
            fromByteOffset = fromByteOffset,
            maxBytes = maxBytes,
        )
        return try {
            when (val reply = consoleSource.read(request)) {
                is ConsoleReadResult.Page -> FabricOutputReadResult.Page(
                    console = reply.page.console,
                    fromByteOffset = reply.page.fromByteOffset,
                    nextByteOffset = reply.page.nextByteOffset,
                    text = reply.page.text,
                    atEndOfStream = reply.page.atEndOfStream,
                )
                is ConsoleReadResult.Refused -> mapRefusal(reply.refusal)
            }
        } catch (failure: FabricTransportFailure) {
            // The seam: a transport failure is a caller-visible
            // Refused, not Unknown.
            FabricOutputReadResult.Refused(
                "transport failure on ConsoleSource.read: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricOutputReadResult.Refused(
                "unexpected error on ConsoleSource.read: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * Map a Fabric-side [ConsoleReadRefusal] to a Studio-side
     * [FabricOutputReadResult].
     *
     * `UnknownConsole` is the one arm that maps to `Unknown` —
     * "the run is here, the console handle isn't" is the same
     * user-visible fact as "we have no binding for this
     * studioRunId". Every other arm is `Refused` with a
     * human-readable string.
     *
     * The 13 arms are covered exhaustively (the Kotlin compiler
     * holds the seam; a future Fabric version adding an arm is
     * a compile error here, not a silent miss).
     */
    private fun mapRefusal(refusal: ConsoleReadRefusal): FabricOutputReadResult =
        when (refusal) {
            is ConsoleReadRefusal.UnknownConsole ->
                FabricOutputReadResult.Unknown(
                    "unknown console: ${refusal.console.value}",
                )
            is ConsoleReadRefusal.ForeignConsole ->
                FabricOutputReadResult.Refused(
                    "foreign console: ${refusal.console.value} belongs to run " +
                        "${refusal.runId.value}, not to ${refusal.runId.value}",
                )
            is ConsoleReadRefusal.NegativeCursor ->
                FabricOutputReadResult.Refused(
                    "negative cursor: fromByteOffset=${refusal.fromByteOffset}",
                )
            is ConsoleReadRefusal.EmptyWindow ->
                FabricOutputReadResult.Refused(
                    "empty window: maxBytes=${refusal.maxBytes}",
                )
            is ConsoleReadRefusal.WindowAboveMaximum ->
                FabricOutputReadResult.Refused(
                    "window above maximum: maxBytes=${refusal.maxBytes} > " +
                        "maximumBytes=${refusal.maximumBytes}",
                )
            is ConsoleReadRefusal.CursorPastEndOfStream ->
                FabricOutputReadResult.Refused(
                    "cursor past end of stream: fromByteOffset=${refusal.fromByteOffset} " +
                        "readableBytes=${refusal.readableBytes} (transient; retry)",
                )
            is ConsoleReadRefusal.ReadAfterTermination ->
                FabricOutputReadResult.Refused(
                    "read after termination: fromByteOffset=${refusal.fromByteOffset} " +
                        "terminatedAtByteOffset=${refusal.terminatedAtByteOffset} (terminal; stop)",
                )
            is ConsoleReadRefusal.OffsetsDiscarded ->
                FabricOutputReadResult.Refused(
                    "offsets discarded: fromByteOffset=${refusal.fromByteOffset} " +
                        "reReadableBytes=${refusal.reReadableBytes} (re-anchor elsewhere)",
                )
            is ConsoleReadRefusal.BytesNotBacked ->
                FabricOutputReadResult.Refused(
                    "bytes not backed: requestedEnd=${refusal.requestedEnd} " +
                        "readableBytes=${refusal.readableBytes} (defect; not recoverable)",
                )
            is ConsoleReadRefusal.SourceNotReady ->
                FabricOutputReadResult.Refused(
                    "source not ready: console=${refusal.console.value} (transient; retry)",
                )
            is ConsoleReadRefusal.CursorNotOnCharacterBoundary ->
                FabricOutputReadResult.Refused(
                    "cursor not on character boundary: fromByteOffset=${refusal.fromByteOffset} " +
                        "characterStartByteOffset=${refusal.characterStartByteOffset} " +
                        "characterByteLength=${refusal.characterByteLength}",
                )
            is ConsoleReadRefusal.WindowTooSmall ->
                FabricOutputReadResult.Refused(
                    "window too small: fromByteOffset=${refusal.fromByteOffset} " +
                        "windowBytes=${refusal.windowBytes} < minimumBytes=${refusal.minimumBytes}",
                )
            is ConsoleReadRefusal.PartialCharacterAtEnd ->
                FabricOutputReadResult.Refused(
                    "partial character at end: characterStartByteOffset=${refusal.characterStartByteOffset} " +
                        "characterByteLength=${refusal.characterByteLength} " +
                        "bytesSoFar=${refusal.bytesSoFar} (transient; re-read later, do not widen)",
                )
        }
}
