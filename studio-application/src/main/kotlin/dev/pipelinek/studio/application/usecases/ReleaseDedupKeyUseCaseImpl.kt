package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyResult
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyUseCase
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H6.5+ (2026-10-13): the standalone
 * [ReleaseDedupKeyUseCase] that releases the
 * dedup key for a run. The H6.4 unit ships
 * the release as a hook on the cancel path;
 * H6.5+ extracts the hook into a named use
 * case.
 *
 * ## What it does
 *
 * `release(studioRunId)`:
 * 1. reads the binding state via
 *    [StudioMetadataPort.findRun];
 * 2. if the state is
 *    [dev.pipelinek.studio.domain.RunState.CREATED]
 *    (no events yet), calls
 *    [DedupKeyPort.release] and returns
 *    [ReleaseDedupKeyResult.Released];
 * 3. if the state is non-CREATED (RUNNING,
 *    terminal), returns
 *    [ReleaseDedupKeyResult.NotApplicable]
 *    (the release is a noop);
 * 4. on a port failure, returns
 *    [ReleaseDedupKeyResult.Refused] (the
 *    conservative path).
 *
 * ## Why a 3-arm result (and not 2)
 *
 * The H6.1 4-arm `DedupRunResult` discipline
 * (NewRun / DedupedRun / Refused / Unknown)
 * is load-bearing. H6.5+ mirrors the
 * discipline: `Released` (success), `NotApplicable`
 * (the binding state is not CREATED; the
 * release is a noop — a future request
 * with the same content will hit the
 * existing run), `Refused` (transport
 * failure). No `Unknown` arm: the
 * `NotApplicable` arm covers the "port
 * does not know" case.
 */
class ReleaseDedupKeyUseCaseImpl(
    private val metadata: StudioMetadataPort,
    private val dedupKeyPort: DedupKeyPort,
) : ReleaseDedupKeyUseCase {

    override suspend fun release(studioRunId: RunId): ReleaseDedupKeyResult = try {
        val binding = metadata.findRun(studioRunId)
            ?: return ReleaseDedupKeyResult.NotApplicable
        if (binding.state != dev.pipelinek.studio.domain.RunState.CREATED.name) {
            return ReleaseDedupKeyResult.NotApplicable
        }
        val released = dedupKeyPort.release(studioRunId)
        if (released) {
            ReleaseDedupKeyResult.Released
        } else {
            // The port returned false (no row
            // was updated). The dedup key was
            // already null; treat as not
            // applicable (idempotent).
            ReleaseDedupKeyResult.NotApplicable
        }
    } catch (failure: Exception) {
        ReleaseDedupKeyResult.Refused(
            reason = "release failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
