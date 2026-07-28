package ao.snakewarz.lab.tune

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TuneJournalTest {
    @Test
    fun `a decision written out reads back as the decision it was`() {
        val journal = TuneJournal(temporary())
        val decision = decision(knob = "cpuct", proposal = "cpuct=0.5", verdict = "BETTER")

        journal.append(decision)

        val read = journal.read().single()
        assertEquals(decision.pass, read.pass)
        assertEquals(decision.stride, read.stride)
        assertEquals("cpuct", read.knob)
        assertEquals("cpuct=0.5", read.proposal)
        assertEquals(1234L, read.seed)
        assertTrue(read.accepted)
    }

    @Test
    fun `decisions accumulate in the order they were taken`() {
        // Which is what makes a resume sound: the search walks the same path by replaying them.
        val journal = TuneJournal(temporary())

        journal.append(decision(knob = "a", proposal = "a=1", verdict = "NO_BETTER"))
        journal.append(decision(knob = "b", proposal = "b=2", verdict = "BETTER"))
        journal.append(decision(knob = "a", proposal = "a=3", verdict = "UNDECIDED"))

        assertEquals(listOf("a", "b", "a"), journal.read().map { it.knob })
        assertEquals(listOf(false, true, false), journal.read().map { it.accepted })
    }

    @Test
    fun `an unwritten journal is nothing rather than a failure`() {
        assertEquals(emptyList(), TuneJournal(temporary()).read())
    }

    @Test
    fun `a torn final line is dropped rather than replayed as a decision`() {
        // What a sweep killed overnight leaves behind, and the case a resume must not act on.
        val file = temporary()
        val journal = TuneJournal(file)
        journal.append(decision(knob = "a", proposal = "a=1", verdict = "BETTER"))
        file.writeText(file.readLines().joinToString("\n", postfix = "\n3\t2\tcpuct"))

        assertEquals(1, journal.read().size)
    }

    @Test
    fun `a field that would corrupt the journal is refused rather than written`() {
        val journal = TuneJournal(temporary())

        assertFailsWith<IllegalArgumentException> {
            journal.append(decision(knob = "cp\tuct", proposal = "x=1", verdict = "BETTER"))
        }
    }

    @Test
    fun `the confirming run is recorded too, and is not a step of the search`() {
        // It is the only row anybody acts on, so it is written down; and it was taken against other
        // seeds at another bound, so a resume must not seat it in the middle of the descent.
        val journal = TuneJournal(temporary())
        journal.append(decision(knob = "cpuct", proposal = "cpuct=2.2", verdict = "BETTER"))
        journal.append(
            TuneJournal.Decision(
                pass = -1,
                stride = 0,
                knob = "cpuct",
                incumbent = "stock",
                proposal = "cpuct=2.2",
                seed = 1_000_001L,
                verdict = "NO_BETTER",
                elo = "-19",
                boards = 800,
            ),
        )

        val read = journal.read()
        assertEquals(listOf(false, true), read.map { it.confirming })
        assertEquals(1, read.filter { !it.confirming }.size)
    }

    private fun decision(knob: String, proposal: String, verdict: String) = TuneJournal.Decision(
        pass = 2,
        stride = 4,
        knob = knob,
        incumbent = "stock",
        proposal = proposal,
        seed = 1234L,
        verdict = verdict,
        elo = "73",
        boards = 60,
    )

    private fun temporary(): Path = Files.createTempDirectory("snakewarz-tune").resolve("journal.tsv")
}
