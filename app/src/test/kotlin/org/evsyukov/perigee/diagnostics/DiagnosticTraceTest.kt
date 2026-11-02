package org.evsyukov.perigee.diagnostics

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The diagnostic build's trace in files of its own: what it keeps of a crashed run, and within what bounds. */
class DiagnosticTraceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "diagnostics")

    private fun open(header: String) = DiagnosticTrace.open(dir, header)

    /** The lines of a snapshot that are events, not the header of a part. */
    private fun events(snapshot: String) = snapshot.lines().filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun dirBytes() = dir.listFiles().orEmpty().sumOf { it.length() }

    @Test
    fun aCrashedRunIsKeptThroughAServiceRestartAndAnActivityRelaunch() {
        val crashed = open("run=crashed")
        crashed.event("prefs.request", "aa=false>true")
        crashed.event("native.render.locked", "w=1080 h=2000")
        // Not closed: a process that aborts closes nothing, and what it wrote must be there all the same.
        val service = open("run=service")
        service.event("work.start", "")
        val activity = open("run=activity")

        val text = activity.snapshot()
        val order = listOf("run=crashed", "prefs.request aa=false>true", "native.render.locked w=1080 h=2000", "run=service", "work.start", "run=activity")
        val at = order.map { text.indexOf(it) }
        assertTrue("every part is there in the order of the runs: $at in\n$text", at.all { it >= 0 } && at == at.sorted())
        assertEquals(listOf(crashed.session + 1, crashed.session + 2), listOf(service.session, activity.session))
    }

    @Test
    fun onlyTheLastFewRunsAreKept() {
        val runs = (1..DiagnosticTrace.SESSIONS + 2).map { n -> open("run=$n").also { it.event("mark", "run=$n") } }
        val text = runs.last().snapshot()
        val kept = (1..DiagnosticTrace.SESSIONS + 2).filter { "mark run=$it" in text }
        assertEquals((3..DiagnosticTrace.SESSIONS + 2).toList(), kept)
        val sessions = dir.list().orEmpty().map { it.substringBefore('.') }.toSet()
        assertEquals(DiagnosticTrace.SESSIONS, sessions.size)
    }

    @Test
    fun aLongRunKeepsItsLatestEventsWithinItsShare() {
        val trace = open("run=long")
        val padding = "x".repeat(200)
        repeat(5000) { trace.event("long", "n=$it pad=$padding") }

        dir.listFiles().orEmpty().forEach { assertTrue("${it.name} is ${it.length()} bytes", it.length() <= DiagnosticTrace.PART_BYTES) }
        assertTrue("${dirBytes()} bytes for one run", dirBytes() <= 2L * DiagnosticTrace.PART_BYTES)
        val text = trace.snapshot()
        assertTrue("the latest event is kept", "long n=4999 " in text)
        assertFalse("the earliest went first", "long n=0 " in text)
        // Every part starts with the header, so the process the events came from is named with them.
        assertTrue(text.lines().first().startsWith("#") && "run=long" in text.lines().first())
        assertEquals("5000", events(text).last().substringBefore(' '))
    }

    @Test
    fun manyLongRunsStayWithinAMebibyte() {
        val padding = "y".repeat(300)
        repeat(DiagnosticTrace.SESSIONS + 3) { run ->
            val trace = open("run=$run")
            repeat(3000) { trace.event("long", "n=$it pad=$padding") }
        }
        assertTrue("${dirBytes()} bytes", dirBytes() <= 1024L * 1024)
    }

    @Test
    fun anEntryIsBoundedAndStaysOnOneLine() {
        val trace = open("run=entry")
        trace.event("font", "family=" + "Ж\nfake.event injected=1\r".repeat(2000))
        trace.event("after", "n=1")
        val lines = events(trace.snapshot())
        assertEquals("one line per event: $lines", 2, lines.size)
        assertTrue(lines[0].length < DiagnosticTrace.LINE_BYTES)
        assertTrue("printable ASCII only: ${lines[0].take(80)}", lines[0].all { it in ' '..'~' })
        assertTrue(lines[1].endsWith("after n=1"))
    }

    @Test
    fun aSnapshotTakenWhileThreadsWriteHasWholeLinesInOrder() {
        val trace = open("run=threads")
        val start = CountDownLatch(1)
        val writers = (1..4).map { w ->
            thread {
                start.await()
                repeat(3000) { trace.event("write", "w=$w n=$it pad=${"z".repeat(100)}") }
            }
        }
        start.countDown()
        val shot = Regex("""(\d+) (\d+) \S+ write w=\d n=\d+ pad=z{100}""")
        while (writers.any { it.isAlive }) checkWholeAndOrdered(trace.snapshot(), shot)
        writers.forEach { it.join() }
        val last = checkWholeAndOrdered(trace.snapshot(), shot)
        assertEquals(12000L, last)
        assertNull(trace.failure)
    }

    /** Each part of the run: whole lines, each one number on from the last, at a time no earlier. Returns the last number. */
    private fun checkWholeAndOrdered(text: String, line: Regex): Long {
        var seq = -1L
        var time = -1L
        for (it in text.lines()) {
            if (it.isEmpty()) continue
            if (it.startsWith("#")) {
                seq = -1
                continue
            }
            val match = line.matchEntire(it) ?: throw AssertionError("not a whole line: $it")
            val (n, t) = match.destructured
            if (seq >= 0) assertEquals("after $seq", seq + 1, n.toLong())
            assertTrue("time goes back at $n", t.toLong() >= time)
            seq = n.toLong()
            time = t.toLong()
        }
        return seq
    }

    @Test
    fun malformedFilesAreReadWithinBoundsAndDoNotStopTheTrace() {
        dir.mkdirs()
        val noise = ByteArray(3 * DiagnosticTrace.PART_BYTES) { (it * 7919 % 256).toByte() }
        File(dir, "session-7.log").writeBytes(noise)
        File(dir, "session-3.prev.log").writeBytes(noise.copyOf(1000))
        File(dir, "session-x.log").writeText("not a run")
        File(dir, "session-8.log").mkdirs()

        val trace = open("run=after-noise")
        trace.event("still", "n=1")
        assertEquals(9L, trace.session)
        assertNull(trace.failure)
        val text = trace.snapshot()
        assertTrue("still n=1" in text)
        assertTrue("${text.length} chars", text.length <= DiagnosticTrace.SESSIONS * 2 * DiagnosticTrace.PART_BYTES)
        assertTrue("printable text only", text.all { it == '\n' || it in ' '..'~' })
    }

    @Test
    fun aDirectoryThatCannotBeMadeLeavesTheTraceOffWithAReason() {
        folder.root.resolve("diagnostics").writeText("a file where the directory should be")
        val trace = open("run=nowhere")
        trace.event("lost", "n=1")
        assertNotNull(trace.failure)
        assertFalse("lost" in trace.snapshot())
    }

    @Test
    fun aWriteThatFailsStopsTheTraceWithoutThrowing() {
        val trace = open("run=broken")
        trace.event("before", "n=1")
        dir.deleteRecursively()
        dir.writeText("the directory is gone")
        // Enough to fill a part: the next one cannot be made.
        repeat(DiagnosticTrace.PART_BYTES / 100 + 10) { trace.event("after", "n=$it pad=${"w".repeat(100)}") }
        assertNotNull(trace.failure)
        trace.snapshot()
    }

    @Test
    fun theTraceIsOffAndWritesNothingUnlessStarted() {
        // Started only by the diagnostic build's provider, which no unit test makes.
        assertFalse(Diagnostics.enabled)
        Diagnostics.event("never") { throw AssertionError("the fields are not even made when the trace is off") }
    }
}
