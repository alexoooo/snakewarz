package ao.snakewarz.lab

import ao.snakewarz.bots.ShippedBots

/**
 * The lab, from a command line.
 *
 * `:app` injects [ShippedBots] into the browser; this injects the same registry into the same
 * `Tournament`, which is what makes a batch run here comparable with one run there rather than merely
 * similar. `:match` still resolves every slot through the `BotRegistry` interface and has still never
 * seen a bot class.
 *
 * ```
 * ./gradlew :lab:run --args="play puct:eval=territory puct:eval=survival --rounds 40 --budget 40000"
 * ./gradlew :lab:run --args="time puct:eval=survival --budget 40000"
 * ```
 *
 * A parse failure is reported and exits non-zero rather than printing a stack trace: unlike a bot
 * reading a knob, there is something above this to catch a throw, which is the whole reason the
 * parser is allowed to be strict.
 */
public fun main(args: Array<String>) {
    try {
        LabCommand.of(args.toList(), ShippedBots).run(ShippedBots, ::println)
    } catch (failure: IllegalArgumentException) {
        report(failure)
    } catch (failure: IllegalStateException) {
        report(failure)
    }
}

private fun report(failure: Throwable): Nothing {
    println("[lab] ${failure.message}")
    kotlin.system.exitProcess(2)
}
