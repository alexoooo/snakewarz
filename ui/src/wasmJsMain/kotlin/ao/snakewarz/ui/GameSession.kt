package ao.snakewarz.ui

import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.MatchEnd
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.SnakeId
import ao.snakewarz.match.InputBuffer
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchRecord
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.ReplayCodec
import ao.snakewarz.match.StepResult
import kotlinx.browser.window

/**
 * The live game: a match, a renderer, a scheduler and the chrome around them, wired together.
 *
 * This is the only public thing in `:ui`. `:app` builds one, hands it a registry, a keyboard and a
 * way to publish a link, and is then done — everything else here is private, so the module's surface
 * is a constructor and two methods rather than a renderer, a scheduler and a pile of DOM.
 *
 * Playing and replaying are the same code path throughout. A replay is a match whose slots already
 * know what they are going to do, so play, pause, step, restart and the scoreboard all work on one
 * without a single branch; only seeking is replay-specific, because only a recording has a future to
 * wind into.
 */
public class GameSession(
    private val registry: BotRegistry,
    private val input: InputBuffer,
    private val replayLink: ReplayLink,
) {
    private val chrome = Chrome(registry, ::dispatch)
    private val renderer = BoardRenderer(chrome.canvas)
    private val scheduler = TurnScheduler(::advance, ::renderChrome)

    private var match: Match = Match(setupFrom(chrome.readOptions()), registry)

    /** The recording being watched, or `null` while a match is being played for real. */
    private var replay: MatchRecord? = null

    private var awaitingInput = false
    private var shareUrl: String? = null

    /** Opens on [record] if there is one in the URL, and on a fresh match if there is not. */
    public fun start(record: MatchRecord?) {
        scheduler.turnsPerSecond = chrome.turnsPerSecond()

        window.addEventListener("resize") { renderer.fit(match.view) }
        window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change") {
            renderer.applyScheme(prefersDark())
            renderer.fit(match.view)
        }

        if (record == null) {
            begin()
        } else {
            load(record)
        }
    }

    /** Switches to watching [record]. Called again whenever the URL fragment changes under us. */
    public fun load(record: MatchRecord) {
        scheduler.stop()
        input.clear()
        replay = record
        shareUrl = null
        match = Match.playback(record)
        chrome.applySetup(record.setup)
        begin()
    }

    override fun toString(): String = "GameSession($match)"

    // -- the one dispatch -----------------------------------------------------------------------

    private fun dispatch(intent: UiIntent) {
        when (intent) {
            UiIntent.TogglePlay -> if (scheduler.running) pause() else play()

            UiIntent.StepOnce -> {
                scheduler.stop()
                advance()
                renderChrome()
            }

            UiIntent.Restart -> restart()

            UiIntent.Share -> share()

            is UiIntent.StartMatch -> newMatch(intent.options)

            is UiIntent.SetSpeed -> scheduler.turnsPerSecond = intent.turnsPerSecond

            is UiIntent.SeekTo -> seek(intent.turnIndex)

            // Straight into the buffer, and deliberately not into the scheduler: a player who paused
            // meant it, and a keypress is a move they are queueing rather than a request to resume.
            is UiIntent.Steer -> input.push(intent.direction)
        }
    }

    // -- match lifecycle ------------------------------------------------------------------------

    private fun begin() {
        awaitingInput = false
        renderer.fit(match.view)
        scheduler.start()
        renderChrome()
    }

    private fun newMatch(options: MatchOptions) {
        scheduler.stop()
        input.clear()
        replay = null
        shareUrl = null
        match = Match(setupFrom(options), registry)
        begin()
    }

    private fun restart() {
        scheduler.stop()
        input.clear()
        val record = replay
        match = if (record == null) Match(match.setup, registry) else Match.playback(record)
        begin()
    }

    private fun play() {
        // A finished match has nothing left to play, so Play means "again".
        if (match.outcome != null) {
            restart()
            return
        }
        scheduler.start()
        renderChrome()
    }

    private fun pause() {
        scheduler.stop()
        renderChrome()
    }

    /**
     * Winds a recording to [target] by replaying it onto a fresh board.
     *
     * Microseconds for a thousand turns — the engine runs tens of millions of them a second and a
     * scripted slot costs no search at all — so there is nothing to be gained by keeping snapshots
     * around, and a great deal to be gained by not having to keep them consistent.
     */
    private fun seek(target: Int) {
        val record = replay ?: return

        scheduler.stop()
        match = Match.playback(record)
        while (match.turnIndex < target) {
            val result = match.step()
            if (result is StepResult.Finished || result == StepResult.AwaitingInput) {
                break
            }
        }

        awaitingInput = false
        renderer.repaint(match.view)
        renderChrome()
    }

    private fun share() {
        shareUrl = replayLink.publish(ReplayCodec.encode(replay ?: match.record()))
        renderChrome()
        chrome.copyShareUrl()
    }

    private fun setupFrom(options: MatchOptions): MatchSetup =
        MatchSetup.create(options.rows, options.cols, options.slots, options.seed)

    // -- one turn -------------------------------------------------------------------------------

    private fun advance(): TurnScheduler.Progress {
        when (val result = match.step()) {
            is StepResult.Advanced -> {
                awaitingInput = false
                // A fatal move recolours a whole body, which is why the engine reports no dirty
                // cells for it: the snake did not move, it changed what it is.
                if (result.fatal) {
                    renderer.paintSnake(match.view, result.id)
                } else {
                    renderer.paintMove(match.view, result.id, match.events())
                }
            }

            is StepResult.Eliminated -> {
                awaitingInput = false
                renderer.paintSnake(match.view, result.id)
            }

            StepResult.AwaitingInput -> {
                awaitingInput = true
                return TurnScheduler.Progress.AWAITING_INPUT
            }

            is StepResult.Finished -> return TurnScheduler.Progress.FINISHED
        }

        return if (match.outcome == null) TurnScheduler.Progress.CONTINUED else TurnScheduler.Progress.FINISHED
    }

    // -- what the chrome is told ----------------------------------------------------------------

    private fun renderChrome() {
        chrome.render(
            UiModel(
                replay = replay != null,
                running = scheduler.running,
                turnIndex = match.turnIndex,
                turnCount = replay?.turnCount ?: match.turnIndex,
                status = statusText(),
                slots = slotStatuses(),
                shareUrl = shareUrl,
            ),
        )
    }

    private fun statusText(): String {
        val outcome = match.outcome
        if (outcome != null) {
            return outcomeText(outcome)
        }
        if (awaitingInput) {
            // A scripted slot answers `Pending` once it runs off the end of what was recorded, which
            // is how a mid-match share plays back: it stops where the recording did.
            return if (replay == null) "your move — arrow keys or WASD" else "end of the recording"
        }
        return if (scheduler.running) "playing" else "paused"
    }

    private fun outcomeText(outcome: MatchOutcome): String = when (outcome.end) {
        MatchEnd.LAST_SNAKE_STANDING -> "${nameOf(outcome.winner)} wins — last snake standing"
        MatchEnd.ALL_ELIMINATED -> "nobody left standing"
        MatchEnd.TURN_LIMIT -> "a draw — the turn limit ran out"
    }

    private fun slotStatuses(): List<SlotStatus> {
        val winner = match.outcome?.winner
        return List(match.setup.slotCount) { slot ->
            val snake = match.view.snake(SnakeId(slot))
            SlotStatus(
                slot = slot,
                name = nameOf(SnakeId(slot)),
                length = snake.length,
                alive = snake.alive,
                fate = snake.eliminationReason?.let(::fateText) ?: "",
                winner = winner != null && winner.index == slot,
            )
        }
    }

    private fun nameOf(id: SnakeId): String {
        if (id.isNone) {
            return "nobody"
        }
        val slug = match.setup.slots[id.index]
        return registry[slug]?.displayName ?: slug.slug
    }

    private fun fateText(reason: EliminationReason): String = when (reason) {
        EliminationReason.TRAPPED -> "trapped"
        EliminationReason.SUICIDE -> "crashed"
        EliminationReason.RESIGNED -> "resigned"
        EliminationReason.FORFEIT -> "forfeited"
    }
}
