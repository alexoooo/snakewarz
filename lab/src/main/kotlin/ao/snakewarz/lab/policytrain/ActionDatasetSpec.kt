package ao.snakewarz.lab.policytrain

import java.nio.file.Path

/** One explicitly named source of expert-labelled action positions. */
internal class ActionDatasetSpec(
    val label: String,
    val directory: Path,
    val expertSpec: String,
    val positionsPerPhase: Int,
) {
    companion object {
        /**
         * Reads `label|directory|expert|positions`, repeated with semicolons.
         *
         * Pipes are unavailable in Windows paths, bot specs, and the repository's identifier
         * alphabets, so every field has one unambiguous boundary without inventing escaping rules.
         */
        fun parseList(text: String, option: String): List<ActionDatasetSpec> {
            require(text.isNotBlank()) { "--$option names no datasets" }

            val specs = text.split(';').mapIndexed { index, encoded -> parse(encoded, option, index) }
            val labels = LinkedHashSet<String>()
            for (spec in specs) {
                require(labels.add(spec.label)) {
                    "--$option repeats dataset label '${spec.label}'"
                }
            }
            return specs
        }

        private fun parse(encoded: String, option: String, index: Int): ActionDatasetSpec {
            val fields = encoded.split('|')
            require(fields.size == FIELD_COUNT) {
                "--$option dataset ${index + 1} wants label|directory|expert|positions, " +
                    "was '$encoded'"
            }

            val label = fields[0].trim()
            require(LABEL.matches(label)) {
                "--$option dataset ${index + 1} label '$label' must match ${LABEL.pattern}"
            }

            val directory = fields[1].trim()
            require(directory.isNotEmpty() && directory != "none") {
                "--$option dataset '$label' needs a retained log directory"
            }

            val expert = fields[2].trim()
            require(expert.isNotEmpty()) { "--$option dataset '$label' needs an expert entrant" }

            val positions = fields[3].trim().toIntOrNull()
                ?: error("--$option dataset '$label' positions must be a whole number, was '${fields[3]}'")
            require(positions > 0) {
                "--$option dataset '$label' positions must be positive, was $positions"
            }

            return ActionDatasetSpec(label, Path.of(directory), expert, positions)
        }

        private const val FIELD_COUNT = 4
        private val LABEL = Regex("[a-z0-9][a-z0-9-]*")
    }
}
