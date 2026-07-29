package ao.snakewarz.lab.tune

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * A search's own record: one tab-separated row per attempt, appended the moment it lands.
 *
 * A search is a long chain of short experiments and the expensive part is the games rather than the
 * arithmetic between them, so nothing is held until the end. A sweep left running overnight survives
 * a kill, a reboot or somebody needing the machine, and what it tried can be read afterwards without
 * trusting a summary printed at the bottom.
 *
 * The [columns] belong to whichever search is writing; this owns only the file. Tab separated with
 * no escaping, on the same terms as the match log: nothing that reaches here holds a separator, and
 * the writer checks anyway, because one that slipped through would read as plausible data for weeks.
 *
 * **A journal is a record of attempts, not of findings.** Every row here is one cheap experiment
 * against one set of boards, and the best of a hundred of those is a maximum of a noise process. What
 * a search recommends is decided by its confirming run over fresh boards, and by nothing in this
 * file.
 */
internal class Journal(private val file: Path, private val columns: List<String>) {
    /** Every row written so far, the header skipped and anything of the wrong width dropped. */
    fun rows(): List<List<String>> {
        if (!file.exists()) {
            return emptyList()
        }

        return file.readLines()
            .drop(1)
            .map { it.split('\t') }
            .filter { it.size == columns.size }
    }

    fun append(row: List<String>) {
        check(row.size == columns.size) { "a row wants ${columns.size} fields, was ${row.size}" }
        for (field in row) {
            require(field.none { it == '\t' || it == '\n' || it == '\r' }) {
                "a separator inside a field would corrupt the journal silently: '$field'"
            }
        }

        file.parent?.let { Files.createDirectories(it) }
        val text = buildString {
            if (!file.exists()) {
                appendLine(columns.joinToString("\t"))
            }
            appendLine(row.joinToString("\t"))
        }
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
