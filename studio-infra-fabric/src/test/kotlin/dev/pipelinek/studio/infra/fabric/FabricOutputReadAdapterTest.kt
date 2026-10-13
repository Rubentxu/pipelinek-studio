@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.ConsoleRef
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.observation.ConsolePage
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for [FabricOutputReadAdapter] — the third port of
 * the H2.2c contract (H2.2c+2, the console).
 *
 * The seam under test is a [io.jenkins.pipelinek.fabric.contracts.observation.ConsoleSource]
 * (Fabric's port) + a [BindingLookup] (the same seam from
 * H2.2c+1). The tests inject fakes so a unit test does not
 * bring up a gRPC socket.
 *
 * What these tests pin:
 *  - the **Page translation** (the Fabric `ConsolePage` shape
 *    passes through; the byte stream is the same on both
 *    sides);
 *  - the **binding-lookup seam** (a `null` answer is the
 *    adapter's own `Unknown`, and no Fabric call is made);
 *  - the **13-arm `ConsoleReadRefusal` mapping**: `UnknownConsole`
 *    → `Unknown`; the other 12 → `Refused` with a
 *    human-readable string. The exhaustive-when discipline
 *    means a future arm is a compile error;
 *  - the **transport-failure carve-out** (a `FabricTransportFailure`
 *    is `Refused`, not `Unknown` — same rule as H2.2c and
 *    H2.2c+1).
 */
@DisplayName("FabricOutputReadAdapter (SFW-H2.2c+2 console)")
class FabricOutputReadAdapterTest {

    // ---- fixtures -------------------------------------------------------------

    private val studioRunId = RunId("studio-run-1")
    private val fabricRunId = FabricRunId("fabric-run-77")
    private val console = ConsoleRef("console-abc-1")

    private fun bindingWith(map: Map<RunId, FabricRunId> = mapOf(studioRunId to fabricRunId)) =
        BindingLookup { id -> map[id] }

    // ---- read: happy path -----------------------------------------------------

    @Test
    fun `read happy path is Page with the bytes and the next cursor`() = runBlocking {
        val page = ConsolePage(
            console = console,
            fromByteOffset = 0L,
            nextByteOffset = 13L,
            text = "resolved-from-page",
            atEndOfStream = false,
        )
        val fake = FakeConsoleSource().apply { readAnswer = ConsoleReadResult.Page(page) }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Page::class.java, result)
        val p = result as FabricOutputReadResult.Page
        assertEquals(console, p.console)
        assertEquals(0L, p.fromByteOffset)
        assertEquals(13L, p.nextByteOffset)
        assertEquals("resolved-from-page", p.text)
        assertEquals(false, p.atEndOfStream)
        // Translation: the request carried the fabricRunId, not the
        // studioRunId, and the byte offset / max bytes were passed through.
        val sent = fake.readCalls.single()
        assertEquals(fabricRunId, sent.runId)
        assertEquals(console, sent.console)
        assertEquals(0L, sent.fromByteOffset)
        assertEquals(4096, sent.maxBytes)
    }

    @Test
    fun `read at end of stream is Page with atEndOfStream true and empty text`() = runBlocking {
        val page = ConsolePage(
            console = console,
            fromByteOffset = 42L,
            nextByteOffset = 42L,
            text = "",
            atEndOfStream = true,
        )
        val fake = FakeConsoleSource().apply { readAnswer = ConsoleReadResult.Page(page) }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 42L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Page::class.java, result)
        val p = result as FabricOutputReadResult.Page
        assertEquals(42L, p.fromByteOffset)
        assertEquals(42L, p.nextByteOffset)
        assertEquals("", p.text)
        assertEquals(true, p.atEndOfStream)
    }

    // ---- read: binding miss ---------------------------------------------------

    @Test
    fun `read with binding miss is Unknown and never calls ConsoleSource`() = runBlocking {
        val fake = FakeConsoleSource()
        val adapter = FabricOutputReadAdapter(fake, bindingWith(mapOf()))

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Unknown::class.java, result)
        val unknown = result as FabricOutputReadResult.Unknown
        assertTrue(
            unknown.reason.contains("no binding for studioRunId"),
            "got: ${unknown.reason}",
        )
        assertEquals(0, fake.readCalls.size)
    }

    // ---- read: refusal arms ---------------------------------------------------

    @Test
    fun `read with UnknownConsole refusal is Unknown`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(ConsoleReadRefusal.UnknownConsole(console))
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Unknown::class.java, result)
        val unknown = result as FabricOutputReadResult.Unknown
        assertTrue(
            unknown.reason.contains("unknown console") && unknown.reason.contains("console-abc-1"),
            "got: ${unknown.reason}",
        )
    }

    @Test
    fun `read with ForeignConsole refusal is Refused (caller is holding somebody else's cursor)`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(
                ConsoleReadRefusal.ForeignConsole(
                    runId = FabricRunId("fabric-run-other"),
                    console = console,
                ),
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("foreign console") &&
                refused.reason.contains("fabric-run-other"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with CursorPastEndOfStream refusal is Refused (transient)`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(
                ConsoleReadRefusal.CursorPastEndOfStream(
                    fromByteOffset = 100L,
                    readableBytes = 50L,
                ),
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 100L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("cursor past end of stream") &&
                refused.reason.contains("fromByteOffset=100") &&
                refused.reason.contains("readableBytes=50") &&
                refused.reason.contains("transient"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with ReadAfterTermination refusal is Refused (terminal)`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(
                ConsoleReadRefusal.ReadAfterTermination(
                    fromByteOffset = 900L,
                    terminatedAtByteOffset = 800L,
                ),
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 900L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("read after termination") &&
                refused.reason.contains("fromByteOffset=900") &&
                refused.reason.contains("terminatedAtByteOffset=800") &&
                refused.reason.contains("terminal"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with NegativeCursor refusal is Refused`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(ConsoleReadRefusal.NegativeCursor(-1L))
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = -1L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("negative cursor") && refused.reason.contains("fromByteOffset=-1"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with EmptyWindow refusal is Refused`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(ConsoleReadRefusal.EmptyWindow(maxBytes = 0))
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 0)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("empty window") && refused.reason.contains("maxBytes=0"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with WindowAboveMaximum refusal is Refused`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(
                ConsoleReadRefusal.WindowAboveMaximum(maxBytes = 1_000_000, maximumBytes = 65_536),
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 1_000_000)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("window above maximum") &&
                refused.reason.contains("maxBytes=1000000") &&
                refused.reason.contains("maximumBytes=65536"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with PartialCharacterAtEnd refusal is Refused (transient, do not widen)`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readAnswer = ConsoleReadResult.Refused(
                ConsoleReadRefusal.PartialCharacterAtEnd(
                    characterStartByteOffset = 200L,
                    characterByteLength = 4,
                    bytesSoFar = 2,
                ),
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 200L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("partial character at end") &&
                refused.reason.contains("characterByteLength=4") &&
                refused.reason.contains("bytesSoFar=2") &&
                refused.reason.contains("do not widen"),
            "got: ${refused.reason}",
        )
    }

    // ---- read: transport carve-out -------------------------------------------

    @Test
    fun `read with FabricTransportFailure is Refused, not Unknown`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readThrows = FabricTransportFailure(
                callName = "ConsoleSource.read",
                message = "connection refused: no server answered",
            )
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("transport failure") &&
                refused.reason.contains("ConsoleSource.read"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `read with unknown exception is Refused with the exception class name`() = runBlocking {
        val fake = FakeConsoleSource().apply {
            readThrows = IllegalStateException("the spool is on fire")
        }
        val adapter = FabricOutputReadAdapter(fake, bindingWith())

        val result = adapter.read(studioRunId, console, fromByteOffset = 0L, maxBytes = 4096)

        assertInstanceOf(FabricOutputReadResult.Refused::class.java, result)
        val refused = result as FabricOutputReadResult.Refused
        assertTrue(
            refused.reason.contains("IllegalStateException"),
            "got: ${refused.reason}",
        )
    }
}
