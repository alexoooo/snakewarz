package ao.snakewarz.ui

import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.core.Direction
import ao.snakewarz.core.MatchEnd
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.SnakeId
import ao.snakewarz.match.InputBuffer
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchRecord
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.ReplayCodec
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.Tournament
import ao.snakewarz.match.TournamentConfig
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
 *
 * There are two clocks, and which one runs is decided by `Match.interactive` rather than by a mode
 * flag. Bots are paced by [TurnScheduler], because watching them is the point. A match with a person
 * in it is **turn-based**: the scheduler is not started at all, and each keypress plays the round it
 * belongs to. The moment that person is eliminated the match stops being interactive and the
 * scheduler takes over, so the survivors finish the game while they watch.
 */
public class GameSession(
    private val registry: BotRegistry,
    private val input: InputBuffer,
    private val replayLink: ReplayLink,
) {
    private val chrome = Chrome(registry, ::dispatch)
    private val renderer = BoardRenderer(chrome.canvas)
    private val scheduler = TurnScheduler(::advance, ::renderChrome)
    private val batch = TournamentRunner(::batchFrame)

    private var match: Match = Match(setupFrom(chrome.readOptions()), registry)

    /** The recording being watched, or `null` while a match is being played for real. */
    private var replay: MatchRecord? = null

    private var awaitingInput = false
    private var shareUrl: String? = null

    /**
     * The matrix as text, re-rendered when a match of the batch ends rather than once a frame.
     *
     * A frame is worth hundreds of tournament turns and at most one result, so laying the table out
     * per frame would be the one genuinely wasteful thing on this path.
     */
    private var batchTable: String = ""
    private var batchMatchesRendered: Int = -1

    /** Why a batch could not start, shown until one does. */
    private var batchRefusal: String? = null

    /**
     * The batch match the arena is showing.
     *
     * Kept here rather than read off the tournament, because a finished tournament has no current
     * match and the last one it played is still on the board — the scoreboard and the stats have to
     * describe *that* position and not the idle match behind it.
     */
    private var batchBoard: Match? = null

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
        // The chrome greys the transport while a batch owns the board, but the space bar and the
        // step key do not read the DOM's disabled flags. One guard covers both routes in.
        if (batch.running && intent != UiIntent.ToggleTournament) {
            return
        }

        // A finished batch leaves its last position on screen. Anything the player then does to the
        // *match* takes the arena back first, and takes it back with a full repaint — the renderer
        // paints one square at a time, so stepping the match onto a board still showing somebody
        // else's game would leave that game underneath it.
        if (batchBoard != null && intent != UiIntent.ToggleTournament) {
            batchBoard = null
            renderer.fit(match.view)
            // Here rather than left to whatever the intent does next: a turn-based match answers
            // Play by doing nothing at all, and the scoreboard would then still be describing the
            // tournament while the board underneath it had gone back to the player's own game.
            renderChrome()
        }

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

            UiIntent.ToggleTournament -> if (batch.running) stopBatch() else startBatch()

            is UiIntent.SetSpeed -> scheduler.turnsPerSecond = intent.turnsPerSecond

            is UiIntent.SeekTo -> seek(intent.turnIndex)

            is UiIntent.Steer -> steer(intent.direction)
        }
    }

    // -- match lifecycle ------------------------------------------------------------------------

    private fun begin() {
        awaitingInput = false

        // A match starting takes the arena back off the batch, whose table stays on the page. A
        // replay arriving from a hash change is the one route in here that a running batch does not
        // already block, so stopping is not merely tidiness.
        batch.stop()
        batchBoard = null
        renderer.fit(match.view)

        if (match.interactive) {
            // Play up to the player's first move and stop there: any slot ahead of them in the turn
            // order opens, and then the board sits and waits, which is the whole point.
            playRound()
        } else {
            scheduler.start()
            renderChrome()
        }
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
        // A match waiting on a person has no clock to start; it advances when they press a key. The
        // chrome disables the button for exactly this reason, but the space bar does not care.
        if (match.interactive) {
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

    /**
     * The whole of what the sidebar gets to say about a match, and deliberately the only place that
     * says it: everything upstream of here is a form, everything downstream is a match.
     */
    private fun setupFrom(options: MatchOptions): MatchSetup =
        MatchSetup.create(
            rows = options.rows,
            cols = options.cols,
            slots = options.slots.map { it.bot },
            seed = options.seed,
            budgets = IntArray(options.slots.size) { options.slots[it].budgetPerTurn },
            slotParams = options.slots.map { it.params },
        )

    // -- the batch ------------------------------------------------------------------------------

    /**
     * Starts a tournament between the bots seated in the pickers.
     *
     * The board becomes the batch's: every frame paints whichever match it is currently on, which
     * costs one full repaint of a small board and turns a progress bar into something worth watching.
     * The match that was on screen before is dropped rather than kept — Restart and Start match are
     * one click away, and quietly restoring a position somebody has since forgotten about is worse
     * than plainly not having it.
     */
    private fun startBatch() {
        val options = chrome.readTournamentOptions()
        if (!options.ready) {
            batchRefusal = "a tournament needs at least ${TournamentOptions.MINIMUM_CONTESTANTS} " +
                "different bots in the slots"
            renderChrome()
            return
        }

        scheduler.stop()
        input.clear()
        batchRefusal = null
        batchTable = ""
        batchMatchesRendered = -1

        batch.start(
            Tournament(
                TournamentConfig(
                    contestants = options.contestants,
                    rows = options.rows,
                    cols = options.cols,
                    rounds = options.rounds,
                    seed = options.seed,
                ),
                registry,
            ),
        )

        // Geometry is the same for every match of the batch, so the board is measured once here and
        // only ever repainted after that.
        batch.tournament?.let { fitToBatch(it) }
        renderChrome()
    }

    /**
     * Shows the opening position of the batch's first match, before a single turn of it is played.
     *
     * Building that match here costs one board and no search, and it means the arena, the scoreboard
     * and the stats all describe the tournament from the frame it starts rather than from the frame
     * after — the alternative shows one frame of the match this just replaced.
     */
    private fun fitToBatch(tournament: Tournament) {
        val opening = Match(tournament.setupFor(0), registry)
        batchBoard = opening
        renderer.fit(opening.view)
    }

    private fun stopBatch() {
        batch.stop()
        refreshBatchTable()
        renderChrome()
    }

    /** Called once a frame while a batch runs: paint where it has got to, then write the numbers. */
    private fun batchFrame() {
        batch.tournament?.current?.let {
            batchBoard = it
            renderer.repaint(it.view)
        }
        refreshBatchTable()
        renderChrome()
    }

    private fun refreshBatchTable() {
        val tournament = batch.tournament ?: return
        if (tournament.matchesPlayed == batchMatchesRendered) {
            return
        }
        batchMatchesRendered = tournament.matchesPlayed
        batchTable = if (tournament.matchesPlayed == 0) "" else tournament.table.toString()
    }

    private fun batchStatus(): TournamentStatus? {
        val tournament = batch.tournament
        if (tournament == null) {
            val refusal = batchRefusal ?: return null
            return TournamentStatus(running = false, progress = refusal, table = "")
        }

        val played = tournament.matchesPlayed
        val total = tournament.matchCount

        return TournamentStatus(
            running = batch.running,
            progress = when {
                tournament.finished -> "done — $total matches, ${tournament.turnsPlayed} turns"
                !batch.running -> "stopped after $played of $total"
                else -> "match ${played + 1} of $total${seatingText(tournament.current)}"
            },
            table = batchTable,
        )
    }

    /** " — space vs wallhug", or nothing at all in the instant before the first match is seated. */
    private fun seatingText(playing: Match?): String {
        val slots = playing?.setup?.slots ?: return ""
        return " — ${nameOf(slots[0])} vs ${nameOf(slots[1])}"
    }

    // -- one turn -------------------------------------------------------------------------------

    /**
     * Queues a move, and — in a turn-based match — plays the round it belongs to.
     *
     * The buffer is still there because the driver takes moves through it, but in a match where the
     * key *is* the clock it holds a direction for the length of one call: nothing else can run
     * between the push and the turn that consumes it.
     *
     * Both guards matter. A running scheduler already drains the buffer every frame, so pumping
     * here as well would double-step; and a paused bots-only match must not resume because somebody
     * leant on an arrow key.
     */
    private fun steer(direction: Direction) {
        input.push(direction)

        if (match.interactive && !scheduler.running) {
            playRound()
        }
    }

    /**
     * Plays turns until it is the player's move again.
     *
     * One key buys one round: the loop stops on the turn an interactive slot has nothing to play,
     * which is the player's own next turn.
     *
     * That stopping turn is one step *past* the round — asking a player who has nothing queued
     * consumes no turn, it only reports that they are waiting — so the bound is a slot per snake
     * plus that poll. Miss the poll and the match never registers as waiting for anybody, which is
     * the difference between the board saying "your move" and the board saying "paused". The bound
     * itself is there in case a stall policy that keeps moving on its own is ever wired in
     * underneath this view; ordinarily the poll ends the loop well inside it.
     *
     * If the player is eliminated part-way through, nobody is left to press a key — so the clock
     * takes the match over on the spot and the ending plays out at the speed on the slider.
     */
    private fun playRound() {
        var remaining = match.setup.slotCount + 1

        while (remaining > 0 && advance() == TurnScheduler.Progress.CONTINUED) {
            remaining--
            if (!match.interactive) {
                scheduler.start()
                break
            }
        }

        renderChrome()
    }

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
        // The one snapshot the whole frame is built from, batch or no batch: whichever match is on
        // screen is the one being reported on, so the scoreboard, the stats and the board agree.
        val shown = batchBoard ?: match

        chrome.render(
            UiModel(
                replay = replay != null,
                interactive = match.interactive,
                running = scheduler.running,
                turnCount = replay?.turnCount ?: shown.turnIndex,
                status = statusText(shown),
                stats = shown.stats(),
                shareUrl = shareUrl,
                tournament = batchStatus(),
            ),
        )
    }

    /** What the line under the board says about [shown], which is not always the player's match. */
    private fun statusText(shown: Match): String {
        if (batch.running) {
            return "tournament running"
        }

        val outcome = shown.outcome
        if (outcome != null) {
            return outcomeText(shown, outcome)
        }
        if (shown !== match) {
            // A batch stopped part-way through one of its matches.
            return "tournament stopped"
        }
        if (awaitingInput) {
            // A scripted slot answers `Pending` once it runs off the end of what was recorded, which
            // is how a mid-match share plays back: it stops where the recording did.
            return if (replay == null) "your move — arrow keys or WASD" else "end of the recording"
        }
        return if (scheduler.running) "playing" else "paused"
    }

    private fun outcomeText(shown: Match, outcome: MatchOutcome): String = when (outcome.end) {
        MatchEnd.LAST_SNAKE_STANDING -> "${nameOf(shown, outcome.winner)} wins — last snake standing"
        MatchEnd.ALL_ELIMINATED -> "nobody left standing"
        MatchEnd.TURN_LIMIT -> "a draw — the turn limit ran out"
    }

    private fun nameOf(shown: Match, id: SnakeId): String =
        if (id.isNone) "nobody" else nameOf(shown.setup.slots[id.index])

    private fun nameOf(bot: BotId): String = registry[bot]?.displayName ?: bot.slug
}
