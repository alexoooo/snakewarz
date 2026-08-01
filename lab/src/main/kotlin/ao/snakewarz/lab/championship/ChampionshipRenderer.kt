package ao.snakewarz.lab.championship

import ao.snakewarz.lab.strength.Bootstrap
import kotlin.math.abs
import kotlin.math.roundToInt

/** The complete finalist report as deterministic lines for a future command to print. */
internal fun renderChampionship(report: ChampionshipReport): List<String> = buildList {
    add(
        "[championship] run ${report.runId}: empty 8x8, complete openings, " +
            "${report.openingBlocks} blocks x ${report.replications} replications",
    )
    add(
        "[championship] ${report.distinctGames} of ${report.matchCount} finalist matches were distinct games",
    )
    add("[championship] directed cells (score with shared-opening-block ${Bootstrap.CONFIDENCE} interval)")
    for (one in report.specs) {
        for (other in report.specs) {
            if (one == other) {
                continue
            }
            val cell = report.cell(one, other)
            add(
                "  cell $one -> $other: ${percent(cell.score)} " +
                    "[${percent(cell.interval.low)}..${percent(cell.interval.high)}] " +
                    "(${cell.wins}-${cell.draws}-${cell.losses}, n=${cell.played})",
            )
        }
    }

    add("[championship] pure-configuration ranking (5pp maximin band, then rating, then Chrome cost)")
    for ((index, finalist) in report.rankedFinalists.withIndex()) {
        add(
            "  ${index + 1}. ${finalist.spec}: maximin ${percent(finalist.maximin)} " +
                "[${percent(finalist.maximinInterval.low)}..${percent(finalist.maximinInterval.high)}], " +
                "band ${finalist.practicalBand}, rating ${signed(finalist.rating)}, " +
                "Chrome raw worst ${millis(finalist.chromeWorstTurnMillis)} ms",
        )
        add("     worst against ${finalist.worstOpponents.joinToString()}")
        if (finalist.ratingPriorDetermined) {
            add("     warning: common-opponent rating is prior-determined")
        }
    }

    add("[championship] rating residuals at least ${points(NOTABLE_RESIDUAL)}")
    if (report.residuals.isEmpty()) {
        add("  none")
    } else {
        for (residual in report.residuals) {
            add(
                "  ${residual.one} -> ${residual.other}: observed ${percent(residual.observed)}, " +
                    "expected ${percent(residual.expected)}, +${points(residual.difference)}",
            )
        }
    }

    val gate = report.incumbentGate
    if (gate.directCell == null) {
        add("[championship] incumbent gate: KEEP ${gate.incumbent}; it ranks first, so no challenger clears it")
    } else {
        val verdict = if (gate.clears) "CLEAR" else "KEEP"
        add(
            "[championship] incumbent gate: $verdict; ${gate.challenger} -> ${gate.incumbent} " +
                "${Bootstrap.CONFIDENCE} lower bound ${percent(gate.directCell.interval.low)} " +
                "${if (gate.clears) ">" else "<="} ${percent(INCUMBENT_THRESHOLD)}",
        )
    }
}

private fun percent(value: Double): String {
    val tenths = (value * 1_000.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}%"
}

private fun points(value: Double): String {
    val tenths = (value * 1_000.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}pp"
}

private fun signed(value: Double): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun millis(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}"
}
