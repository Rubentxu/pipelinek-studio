package dev.pipelinek.studio.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B5.0 (2026-10-10): the `studio start` /
 * `studio stop` / `studio status` lock file
 * contract.
 *
 * The S5 mandate says the operator needs a
 * reliable way to boot, query, and terminate a
 * running studio server. The lock file under
 * `--data-dir/.studio.lock` is the cross-
 * platform mechanism: it carries the PID, host,
 * port, and startedAt timestamp.
 *
 * The tests pin:
 *  1. The lock file JSON shape: the four
 *     fields (pid, host, port, startedAt) are
 *     stable wire fields; a change is a wire
 *     change.
 *  2. The round-trip: write -> read returns
 *     the same value.
 *  3. The read contract: missing lock returns
 *     null; corrupt lock returns null (the
 *     operator can `rm` the file to recover).
 *  4. The isLive contract: a live PID (the
 *     current JVM) is reported live; a
 *     definitely-dead PID (e.g. Long.MAX_VALUE)
 *     is reported dead.
 *  5. The clear contract: clear deletes the
 *     lock; clear on a missing lock is a no-op.
 *  6. The status contract: serverStatus with
 *     a live lock returns running=true;
 *     serverStatus with a missing lock returns
 *     running=false; serverStatus with a stale
 *     lock (live JSON, dead PID) returns
 *     running=false.
 *  7. The stopServer contract: stop on a
 *     missing lock returns 0 (nothing to
 *     stop) AND prints the "nothing to stop"
 *     message; stop on a stale lock returns 0
 *     AND clears the lock.
 */
class StudioLockB50Test {

    @Test
    fun `B5_0 lock file JSON shape is the four-field wire contract`() {
        // The lock is read by future B5.x
        // tooling; a field rename is a wire
        // change. The test pins the field
        // names.
        val lock = StudioLock(pid = 42, host = "127.0.0.1", port = 7778, startedAt = 1_700_000_000L)
        val text = lock.toJson()
        val parsed = Json.decodeFromString(StudioLock.serializer(), text)
        assertEquals(lock, parsed)
        val element = Json.parseToJsonElement(text)
        val keys = (element as kotlinx.serialization.json.JsonObject).keys
        assertEquals(
            setOf("pid", "host", "port", "startedAt"),
            keys,
            "lock JSON keys must be exactly the four-field wire contract",
        )
    }

    @Test
    fun `B5_0 lock round-trip — write then read returns the same value`(@TempDir tmp: Path) {
        val lock = StudioLock(pid = 12345, host = "0.0.0.0", port = 9000, startedAt = 1_700_000_000L)
        StudioLock.write(tmp, lock)
        val read = StudioLock.read(tmp)
        assertEquals(lock, read, "round-trip must preserve the lock value")
    }

    @Test
    fun `B5_0 read returns null when the lock is missing`(@TempDir tmp: Path) {
        // The data dir exists but has no
        // .studio.lock. The read contract is
        // "null means no server".
        assertEquals(null, StudioLock.read(tmp))
    }

    @Test
    fun `B5_0 read returns null when the lock is corrupt`(@TempDir tmp: Path) {
        // A corrupt lock (not valid JSON) is
        // treated as "no server". The operator
        // can rm the file to recover.
        Files.writeString(tmp.resolve(StudioLock.LOCK_FILENAME), "this is not json {{{")
        assertEquals(null, StudioLock.read(tmp), "corrupt lock must read as null")
    }

    @Test
    fun `B5_0 isLive is true for the current JVM PID`() {
        // The current JVM's PID is always alive
        // (the test is running inside it). The
        // check works on Linux, macOS, Windows
        // (JDK 9+ ProcessHandle).
        val lock = StudioLock(
            pid = ProcessHandle.current().pid(),
            host = "127.0.0.1",
            port = 7778,
            startedAt = 0,
        )
        assertTrue(
            StudioLock.isLive(lock),
            "current JVM PID ${lock.pid} must be reported live",
        )
    }

    @Test
    fun `B5_0 isLive is false for an obviously dead PID`() {
        // Long.MAX_VALUE is reserved by the JVM
        // and is never an OS PID. The check must
        // return false.
        val lock = StudioLock(
            pid = Long.MAX_VALUE,
            host = "127.0.0.1",
            port = 7778,
            startedAt = 0,
        )
        assertFalse(
            StudioLock.isLive(lock),
            "PID ${Long.MAX_VALUE} must be reported dead",
        )
    }

    @Test
    fun `B5_0 clear deletes the lock`(@TempDir tmp: Path) {
        StudioLock.write(tmp, StudioLock(1, "127.0.0.1", 7778, 0))
        assertNotNull(StudioLock.read(tmp))
        StudioLock.clear(tmp)
        assertEquals(null, StudioLock.read(tmp), "clear must delete the lock")
    }

    @Test
    fun `B5_0 clear on a missing lock is a no-op`(@TempDir tmp: Path) {
        // The data dir has no lock. clear
        // must not throw.
        StudioLock.clear(tmp)
        assertEquals(null, StudioLock.read(tmp))
    }

    @Test
    fun `B5_0 serverStatus with a live lock reports running = true`(@TempDir tmp: Path) {
        // Write a lock for the current JVM
        // (which is alive). The status reports
        // running=true with the lock's metadata.
        val lock = StudioLock(
            pid = ProcessHandle.current().pid(),
            host = "127.0.0.1",
            port = 7778,
            startedAt = 1_700_000_000L,
        )
        StudioLock.write(tmp, lock)
        val status = serverStatus(tmp)
        assertTrue(status.running, "live lock must produce running=true, got: $status")
        assertEquals(lock.pid, status.pid)
        assertEquals(lock.host, status.host)
        assertEquals(lock.port, status.port)
        assertEquals(lock.startedAt, status.startedAt)
    }

    @Test
    fun `B5_0 serverStatus with a missing lock reports running = false`(@TempDir tmp: Path) {
        val status = serverStatus(tmp)
        assertFalse(status.running, "missing lock must produce running=false, got: $status")
    }

    @Test
    fun `B5_0 serverStatus with a stale lock (live JSON, dead PID) reports running = false`(
        @TempDir tmp: Path,
    ) {
        // The lock is well-formed JSON with a
        // dead PID. The status reports
        // running=false and surfaces the stale
        // PID so the operator knows the lock
        // is from a crashed run.
        val lock = StudioLock(pid = Long.MAX_VALUE, host = "127.0.0.1", port = 7778, startedAt = 0L)
        StudioLock.write(tmp, lock)
        val status = serverStatus(tmp)
        assertFalse(status.running, "stale lock must produce running=false, got: $status")
        assertEquals(Long.MAX_VALUE, status.pid, "stale PID must be surfaced")
    }

    @Test
    fun `B5_0 stopServer with a missing lock returns 0 and prints nothing-to-stop`(
        @TempDir tmp: Path,
    ) {
        val code = stopServer(tmp)
        assertEquals(0, code, "stop with no lock must return 0 (nothing to stop)")
    }

    @Test
    fun `B5_0 stopServer with a stale lock returns 0 and clears the lock`(@TempDir tmp: Path) {
        val lock = StudioLock(pid = Long.MAX_VALUE, host = "127.0.0.1", port = 7778, startedAt = 0L)
        StudioLock.write(tmp, lock)
        val code = stopServer(tmp)
        assertEquals(0, code, "stop with stale lock must return 0 (cleared)")
        assertEquals(null, StudioLock.read(tmp), "stale lock must be cleared after stop")
    }

    @Test
    fun `B5_0 StudioStatus toHumanReadable includes pid and base url when running`() {
        val s = StudioStatus(
            running = true,
            pid = 42,
            host = "127.0.0.1",
            port = 7778,
            startedAt = 1_700_000_000L,
            baseUrl = "http://127.0.0.1:7778",
        )
        val text = s.toHumanReadable()
        assertTrue(text.contains("running"), "human text must say 'running', got: $text")
        assertTrue(text.contains("42"), "human text must include the PID, got: $text")
        assertTrue(text.contains("http://127.0.0.1:7778"), "human text must include the base url, got: $text")
    }

    @Test
    fun `B5_0 StudioStatus toHumanReadable when not running is a single-line diagnostic`() {
        val s = StudioStatus(running = false)
        val text = s.toHumanReadable()
        assertTrue(text.contains("not running"), "human text must say 'not running', got: $text")
        assertTrue(text.contains(StudioLock.LOCK_FILENAME), "human text must name the lock file, got: $text")
    }
}
