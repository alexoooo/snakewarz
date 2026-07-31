package ao.snakewarz.ui

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.human.InputBuffer
import ao.snakewarz.match.human.PathPlanner
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.match.ladder.Ladder
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.match.tournament.Tournament
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.ui.chrome.Chrome
import ao.snakewarz.ui.chrome.Preferences
import ao.snakewarz.ui.chrome.panel.freshSeed
import ao.snakewarz.ui.model.MatchOptions
import ao.snakewarz.ui.model.Panel
import ao.snakewarz.ui.model.Portraits
import ao.snakewarz.ui.model.ReplayLink
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.SlotLabels
import ao.snakewarz.ui.model.SlotPortraits
import ao.snakewarz.ui.model.TournamentOptions
import ao.snakewarz.ui.model.TournamentStatus
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.model.hoverInfo
import ao.snakewarz.ui.model.ladder.LadderProgress
import ao.snakewarz.ui.render.BoardRenderer
import ao.snakewarz.ui.render.Theme
import ao.snakewarz.ui.render.prefersDark
import ao.snakewarz.ui.schedule.TournamentRunner
import ao.snakewarz.ui.schedule.TurnScheduler
import kotlinx.browser.window

/**
 * The live game: a match, a renderer, a scheduler and the chrome around them, wired together.
 *
 * This is the only public class in `:ui`. `:app` builds one, hands it a registry, a keyboard, a way
 * to publish a link and a source of pictures, and is then done — everything else here is private, so
 * the module's surface is a constructor and two methods rather than a renderer, a scheduler and a
 * pile of DOM. The other two public declarations are the seams those last two arguments arrive
 * through, [ReplayLink] and [Portraits], and both exist because the thing on the far side of them is
 * `:app`'s to know.
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
    private val portraits: Portraits,
) {
    private val chrome = Chrome(registry, portraits, ::dispatch)
    private val renderer = BoardRenderer(chrome.canvas, chrome.overlay)
    private val scheduler = TurnScheduler(::advance, ::renderChrome)
    private val batch = TournamentRunner(::batchFrame)

    private var match: Match = Match(setupFrom(chrome.readOptions()), registry)

    /**
     * The route the player is drawing across the board.
     *
     * One planner per board, because its buffers are sized off the grid — [begin] builds the one
     * the match it is starting will use, and no route outlives the match it was drawn in.
     */
    private var plan: PathPlanner = PathPlanner(match.grid)

    /**
     * Whether a pointer is still holding that route.
     *
     * The difference between a plan being drawn and one that has been let go of, and therefore the
     * whole of "release stops the snake": a route lives exactly as long as the press that drew it.
     * Not the same question as `PathInput.pressing`, which is only whether a pointer is down — a
     * press that landed nowhere near the head holds nothing.
     */
    private var dragging: Boolean = false

    /** The recording being watched, or `null` while a match is being played for real. */
    private var replay: MatchRecord? = null

    /** Which section the page is showing. Only [Screen.GAME] has a board, so only it has a clock. */
    private var screen: Screen = Screen.HOME

    /**
     * The rung of the ladder on the board, or `null` for a match somebody configured.
     *
     * The one source of truth for "this is a level": the mode the chrome gates panels on is derived
     * from it, the bar names it, the verdict offers the one above it and progress is keyed on it. It
     * outlives the screen it was chosen on — a level is played on the game screen like anything else
     * — so it is remembered rather than derived from [screen].
     */
    private var level: Int? = null

    /**
     * How far up the ladder this browser has got.
     *
     * Read once, at boot, and written only when a level is beaten. Keeping it here rather than
     * asking the store per frame is what lets a browser that refuses storage still play a whole
     * evening's ladder — the writes go nowhere and the reads never happen.
     */
    private var progress: LadderProgress = LadderProgress.parse(Preferences.ladder())

    /** Whether the level on the board has already been settled, so a win is written down once. */
    private var levelRecorded = false

    /** The panel slid over the board, or `null`. Never more than one, and never beside the board. */
    private var openPanel: Panel? = null

    /**
     * The theme the player chose, and that theme resolved against the scheme their system is in.
     *
     * Two fields, because they answer to different people. The id is remembered across visits and
     * survives the sun going down; the instance is what the board and the page are painted from and
     * is recomputed whenever either half moves. `Theme.of` is total, so an id from a version that
     * offered more themes opens on the default rather than taking the page down.
     */
    private var themeId: String = Preferences.theme() ?: Theme.DEFAULT_ID
    private var theme: Theme = Theme.of(themeId, prefersDark())

    /**
     * Which seat a person is steering, or `null` for a match nobody is playing by hand.
     *
     * Read off the setup rather than off the driver, because the driver's `interactive` goes false
     * the moment that person is eliminated — which is exactly the match this has to have a verdict
     * for. `PlayableRegistry.HUMAN_ID` names the seat the same way the picker does.
     */
    private var playerSeat: Int? = null

    /** Whether the verdict on the finished match has been put away. Reset by every fresh match. */
    private var resultDismissed = false

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

    /**
     * What the seats of the match on screen are called, and the setup they were worked out from.
     *
     * Cached against the setup *instance* rather than rebuilt per frame: a tournament changes match
     * under this several times a second and every other frame reuses what is already here.
     */
    private var labelledSetup: MatchSetup = match.setup
    private var labels: SlotLabels = SlotLabels(match.setup, registry)

    /**
     * The faces of those seats, and the setup and theme they were resolved against.
     *
     * Cached like [labels] and against one thing more, because a bot with no shipped art is drawn in
     * its seat's trail colour. The **theme id** and not the theme instance is what invalidates them:
     * `Theme.body` is the same string under either scheme, so the sun going down must not spend a
     * hash and a base64 encode per seat to produce the marks that are already there.
     */
    private var portraitedSetup: MatchSetup = match.setup
    private var portraitedTheme: String = theme.id
    private var slotFaces: SlotPortraits = SlotPortraits(match.setup, portraits, theme)

    /**
     * The square the pointer is over, or [Cell.NONE].
     *
     * A square and not a snake, so that a restart, a seek and a batch moving on to its next match
     * all resolve to whoever holds it *now* — the renderer's own rule about reading colour off the
     * position, applied to the pointer.
     */
    private var hovered: Cell = Cell.NONE

    init {
        // Before the page is revealed. `:app` adds `booted` after this constructor returns and #app
        // is `display: none` until it does, so the colours land on a page nobody has looked at yet
        // and there is no frame of the default theme to see.
        paintTheme()
    }

    /**
     * Opens on [record] if there is one in the URL, and on the menu if there is not.
     *
     * Either way a match is built and its opening position painted, because the alternative — a
     * board that only exists once a mode has been picked — would put a first measurement of it after
     * the first frame it is visible on.
     */
    public fun start(record: MatchRecord?) {
        scheduler.turnsPerSecond = chrome.turnsPerSecond()

        window.addEventListener("resize") { refit() }
        // The scheme is the system's half of the theme, so the sun going down recolours the theme
        // the player picked rather than resetting them to the default one.
        window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change") { retheme() }

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
        // A link somebody shared opens on the board it was recorded on, without passing through the
        // menu — and takes the board over if one arrives by hash change while the menu is showing.
        // A shared match is somebody's own configuration rather than a rung of the ladder, so it
        // arrives with the panels that let you take it apart and play it again. Walking out of a
        // level this way costs no progress: progress is only ever written on a win.
        screen = Screen.GAME
        level = null
        openPanel = null
        begin()
    }

    override fun toString(): String = "GameSession($match)"

    // -- the one dispatch

    /**
     * The one way in, and a two-line fork because the tiering is a type.
     *
     * `UiIntent`'s two halves say which side of the guards below an intent is answered on, so the
     * decision travels with the intent instead of being an ordering in here that a rewrite could
     * quietly lose.
     */
    private fun dispatch(intent: UiIntent) {
        when (intent) {
            is UiIntent.Shell -> shellIntent(intent)
            is UiIntent.Match -> matchIntent(intent)
        }
    }

    /**
     * Everything that changes nothing about the match, answered ahead of both guards below.
     *
     * That placement is the whole of it: asking what is under the pointer, re-measuring the board
     * and sliding a panel over it neither have to be dropped while a batch owns the arena nor are
     * grounds for taking the arena back off one. Answer one below either guard and moving the mouse
     * across a finished tournament's last position — or folding a panel away — quietly swaps it for
     * the player's own game.
     *
     * Which intents those are is [UiIntent.Shell] rather than an ordering here, so the decision is
     * taken where the intent is declared and cannot be lost in a rewrite of this function.
     */
    private fun shellIntent(intent: UiIntent.Shell) {
        when (intent) {
            is UiIntent.Hover -> hover(renderer.cellAt(intent.clientX, intent.clientY))

            UiIntent.HoverEnded -> hover(Cell.NONE)

            is UiIntent.PathBegan -> pathBegan(intent.clientX, intent.clientY)

            is UiIntent.PathDragged -> pathDragged(intent.clientX, intent.clientY)

            UiIntent.PathReleased -> endPath()

            UiIntent.Relayout -> refit()

            is UiIntent.OpenPanel -> showPanel(intent.panel)

            UiIntent.ClosePanel -> closeOverlay()

            is UiIntent.SetTheme -> setTheme(intent.id)

            // The one here that costs something, and it stops the clocks itself.
            is UiIntent.Navigate -> navigate(intent.screen)
        }
    }

    /** Everything that acts on the match, behind the two guards that decide whose board is showing. */
    private fun matchIntent(intent: UiIntent.Match) {
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
            refreshOverlay()
            // Here rather than left to whatever the intent does next: a turn-based match answers
            // Play by doing nothing at all, and the scoreboard would then still be describing the
            // tournament while the board underneath it had gone back to the player's own game.
            renderChrome()
        }

        when (intent) {
            UiIntent.TogglePlay -> if (scheduler.running) pause() else play()

            UiIntent.StepOnce -> {
                scheduler.stop()
                // A replay that has run out of recorded moves has nothing left to step: the driver
                // reports that once and throws on a second ask, because for anything but this
                // transport a second ask is a loop that would never end.
                if (!match.playbackExhausted) {
                    advance()
                }
                renderChrome()
            }

            UiIntent.Restart -> restart()

            UiIntent.Share -> share()

            UiIntent.WatchReplay -> watchReplay()

            is UiIntent.StartMatch -> newMatch(intent.options)

            is UiIntent.StartLevel -> startLevel(intent.index)

            UiIntent.ToggleTournament -> if (batch.running) stopBatch() else startBatch()

            is UiIntent.SetSpeed -> scheduler.turnsPerSecond = intent.turnsPerSecond

            is UiIntent.SeekTo -> seek(intent.turnIndex)

            is UiIntent.Steer -> steer(intent.direction)
        }
    }

    // -- the pointer

    private fun hover(cell: Cell) {
        if (cell == hovered) {
            return
        }
        hovered = cell
        refreshOverlay()
        // What the label says is model state and comes down through render; only where it sits does
        // not, and the chrome has already moved it by the time this runs.
        renderChrome()
    }

    /**
     * Repaints the overlay: every snake's thread, and the wash over whoever holds the hovered square
     * *now*.
     *
     * Called after every paint, and for two reasons rather than one. The threads move with the
     * snakes, so they are out of date the instant a turn is played whether or not a pointer is
     * anywhere near the board; and the board moves under a pointer that is standing still, so the
     * square being asked about may have changed hands. The square is re-checked against the grid on
     * the way through, so a match on a different board size cannot be asked about one that no longer
     * exists.
     */
    private fun refreshOverlay() {
        val shown = batchBoard ?: match
        if (!shown.grid.isPlayable(hovered)) {
            hovered = Cell.NONE
        }
        renderer.paintOverlay(shown.view, hovered, plan)
    }

    // -- the drawn route

    /**
     * Takes hold of the snake, when the press landed near enough to its head.
     *
     * Nothing is queued and no clock starts here. A press on its own is somebody putting a finger on
     * their snake; it is the drag that follows which says where it should go, and a press that takes
     * hold of nothing falls straight through to hover in [pathDragged].
     *
     * The board on screen belongs to whatever is being watched, so a batch's board is refused
     * outright: a press on it is not a press on this player's head however close the coordinates
     * come. That refusal is what lets these stay `UiIntent.Shell` intents — a pointer dragged over a
     * running tournament then genuinely changes nothing about it, which is the claim their tier
     * makes.
     */
    private fun pathBegan(clientX: Double, clientY: Double) {
        endPath()

        if (batch.running || batchBoard != null || !match.interactive) {
            return
        }
        val seat = playerSeat ?: return
        val head = match.view.snake(SnakeId(seat)).head
        if (!nearHead(match.grid, renderer.cellAt(clientX, clientY), head)) {
            return
        }

        plan.begin(head)
        dragging = true
        // A key pressed a moment ago is not part of the route being drawn, and the first thing the
        // clock below does is play whatever is queued.
        input.clear()
        // The label and the wash get out of the way: on a phone the tip would sit under the finger
        // drawing the route, and while one is being drawn it is the only cue that matters.
        hover(Cell.NONE)
    }

    /**
     * Routes the plan on to the square under the pointer, and starts the clock that walks it.
     *
     * **This is the fourth thing that can drive an interactive match, and the only paced one.** A
     * keypress plays the round it belongs to and stops; a drawn route hands the match to
     * [TurnScheduler], so the player can hold still and watch the snake walk what they drew at the
     * speed on the slider.
     *
     * A refused extension is an ordinary answer during a drag rather than a fault — the pointer is
     * off the board, on a wall, on a body, or in a pocket the route has sealed behind itself — and
     * it means keep the last route that worked. The queue is rewritten either way, because the snake
     * may have walked part of that route since the last event.
     *
     * A route that empties while still held needs nothing: `InteractiveBot` answers `Pending`,
     * `Match.step` reports `AwaitingInput`, and the scheduler clamps its accumulator and waits, so
     * no debt builds up while the player thinks. Dragging further refills the queue and the next
     * frame carries on.
     */
    private fun pathDragged(clientX: Double, clientY: Double) {
        val target = renderer.cellAt(clientX, clientY)
        if (!dragging) {
            // A press that took hold of nothing is an ordinary pointer on the board: it asks what is
            // under it, exactly as one that was never pressed does.
            hover(target)
            return
        }

        plan.extend(match.view, target)
        input.replace(plan.directions, plan.moveCount)
        refreshOverlay()

        if (plan.isEmpty || scheduler.running) {
            return
        }
        scheduler.start()
        renderChrome()
    }

    /**
     * Ends the route, empties the queue it filled, and stops the clock it started.
     *
     * **This is what letting go does, and it is the whole of "release stops the snake":** what was
     * left of the route is discarded rather than played out, so the snake halts on the square it is
     * on that turn. An arrow key and a tournament taking the arena end a drag by the same call,
     * because there is one route and one way it ends.
     */
    private fun endPath() {
        // Asked before anything is done rather than after: with no route in play this is a click on
        // the board, and a click must not stop the clock of a match of bots somebody is watching.
        if (!dragging && plan.cellCount == 0) {
            return
        }
        forgetPath()
        scheduler.stop()
        refreshOverlay()
        renderChrome()
    }

    /**
     * Drops the route and the queue it filled, without touching the clock.
     *
     * The half of [endPath] that the player being eliminated needs: their route has no head left to
     * be anchored on, but the survivors still have a game to finish and the scheduler is what
     * finishes it.
     */
    private fun forgetPath() {
        dragging = false
        plan.clear()
        input.clear()
    }

    /**
     * Keeps the route level with the snake walking it.
     *
     * A route is anchored on the head, so something has to drop its first square as the snake takes
     * one — without this the painted plan trails a square further behind on every move the player
     * makes. Two things then leave the anchor off the head, and both are ordinary. A snake that is
     * out has no head at all. And `InputBuffer.take` discards a queued direction that has become
     * illegal rather than playing it, so the snake can land on a square the route did not spell out
     * — after which the drag carries on from where the snake actually is, which is better than
     * painting a route a square off it.
     */
    private fun consumePlan(result: StepResult) {
        val seat = playerSeat ?: return

        val snake = match.view.snake(SnakeId(seat))
        if (!snake.alive) {
            forgetPath()
            return
        }
        if (result !is StepResult.Advanced || result.id.index != seat) {
            return
        }

        plan.advance()
        if (dragging && (plan.cellCount == 0 || plan.cellAt(0) != snake.head)) {
            plan.begin(snake.head)
        }
    }

    /**
     * Re-measures and repaints whichever match is on screen.
     *
     * Resize, the theme changing, and anything that moves the chrome the board's track shares a
     * column with — [UiIntent.Relayout], and entering or leaving replay, which reveals the scrub row.
     */
    private fun refit() {
        val shown = batchBoard ?: match
        renderer.fit(shown.view)
        refreshOverlay()
    }

    // -- colour

    /**
     * Switches to the theme called [id] and remembers the choice.
     *
     * Stored before it is painted, because the store is allowed to fail — a browser that will not
     * hand over its storage still gets the theme it was asked for, for this visit.
     */
    private fun setTheme(id: String) {
        themeId = id
        Preferences.setTheme(id)
        retheme()
    }

    /** The theme at [themeId] under the scheme now in force, and everything it colours. */
    private fun retheme() {
        paintTheme()
        // Through `fit` rather than `repaint`, because the gridlines are laid down once per resize
        // and they change colour too.
        refit()
        // The seat cards take their swatch from the model, and a theme can move a trail hue.
        renderChrome()
    }

    /**
     * Resolves the theme and hands it to the two things painted from it.
     *
     * Split from [retheme] because this half is all a page with no board yet can do — the boot path
     * runs it before anything has been measured, so that the page is never seen in a theme nobody
     * chose.
     */
    private fun paintTheme() {
        theme = Theme.of(themeId, prefersDark())
        theme.applyToPage()
        renderer.applyTheme(theme)
    }

    // -- the shell

    /**
     * Shows another screen, and takes both clocks with it.
     *
     * The one navigation that costs anything. Only the game screen has a board, so a match left
     * running behind the menu would be a game nobody can see playing itself out — and a tournament
     * would go on spending frames on a board that is not on the page.
     *
     * The chrome is rendered *before* the board is measured, which is the same ordering constraint
     * [begin] observes for the scrub row: the board's track belongs to the screen that just
     * appeared, so measuring first measures the one that is leaving.
     */
    private fun navigate(target: Screen) {
        if (target == screen) {
            return
        }

        scheduler.stop()
        batch.stop()
        // A route is a pointer held on a board that is about to leave the screen, and a queue full
        // of moves for a match nobody can see. The clock above already stopped.
        forgetPath()
        // Every way onto the board that goes through here is one of the menu's own buttons, because
        // a level arrives by [startLevel] and not by navigating. So the board this reaches is always
        // a match somebody configured, and a level left behind on it stops being the one being
        // played — which costs nothing, since progress is only ever written on a win.
        if (target == Screen.GAME) {
            level = null
        }
        screen = target
        // A panel belongs to the board it covers, so it does not follow you off the screen.
        openPanel = null

        renderChrome()
        refit()
    }

    /** Slides a panel over the board. No refit: an overlay leaves the board's box exactly as it was. */
    private fun showPanel(panel: Panel) {
        openPanel = panel
        renderChrome()
    }

    /**
     * Puts away whatever is on top.
     *
     * Which that is lives here rather than in the chrome, for the reason [UiIntent.TogglePlay]'s
     * meaning does: Escape, a close button and the dimmed backdrop all say "the thing in front of
     * me", and only the session knows whether that is the verdict or a panel.
     */
    private fun closeOverlay() {
        if (resultText() != null) {
            resultDismissed = true
        } else {
            openPanel = null
        }
        renderChrome()
    }

    /**
     * The verdict on the match the player just finished, or `null` when there is none to give.
     *
     * Theirs and nobody else's: a batch's matches belong to the tournament, a recording has been
     * watched before, and a board of bots has no "you" to have won. Derived every frame rather than
     * raised as an event, so a restart, a seek or a batch taking the arena all clear it by making
     * the question answer differently — the same rule every colour on this board follows.
     */
    private fun resultText(): String? {
        if (screen != Screen.GAME || resultDismissed || replay != null || batch.running || batchBoard != null) {
            return null
        }
        val seat = playerSeat ?: return null
        val outcome = match.outcome ?: return null

        return when {
            // Beating the top rung is the one win worth a different word: there is no level above it
            // to be offered, so the card would otherwise say "You win" and hand back a lone Home
            // button with no explanation.
            outcome.winner.index == seat -> if (level == Ladder.size) "Ladder complete" else "You win"
            outcome.end == MatchEnd.TURN_LIMIT -> "A draw"
            else -> "You lose"
        }
    }

    /**
     * Whether the player has just beaten the level on the board.
     *
     * Asked of the match rather than remembered, for the reason [resultText] is derived: a restart, a
     * seek and a fresh level all clear it by making the question answer differently.
     */
    private fun levelWon(): Boolean {
        val seat = playerSeat ?: return false
        return level != null && replay == null && match.outcome?.winner?.index == seat
    }

    /**
     * Writes a beaten level down, once, and unlocks the one above it.
     *
     * Only on a win — losing a level costs nothing at all, which is what unlimited lives means — and
     * only on the player's own match, so a recording of somebody else's level cannot clear one for
     * them. The store is allowed to fail and says nothing when it does; the unlock still stands for
     * this visit.
     */
    private fun recordLevelWin() {
        if (levelRecorded) {
            return
        }
        levelRecorded = true

        val beaten = level ?: return
        if (!levelWon()) {
            return
        }
        progress = progress.withCleared(beaten)
        Preferences.setLadder(progress.format())
    }

    /**
     * The face that goes beside that verdict: whoever won.
     *
     * Which is your own on a win and your opponent's on a loss, so the dialog shows who the game was
     * against rather than repeating a word. A draw has nobody to show and gets nothing — the seat
     * cards behind the dialog carry every face either way.
     */
    private fun winnerFace(faces: SlotPortraits): String? {
        val winner = match.outcome?.winner ?: return null
        return if (winner.isNone) null else faces[winner.index]
    }

    // -- match lifecycle

    private fun begin() {
        awaitingInput = false
        resultDismissed = false
        levelRecorded = false
        playerSeat = match.setup.slots.indexOfFirst { it == PlayableRegistry.HUMAN_ID }.takeIf { it >= 0 }

        // A planner's buffers are sized off the board it plans on, so a match on a different board
        // needs its own. The one being replaced is emptied rather than merely dropped, because the
        // renderer holds whichever it was last handed — a route drawn on the board that just left
        // would otherwise be painted once more, against the new board's geometry, by the fit below.
        forgetPath()
        plan = PathPlanner(match.grid)

        // A match starting takes the arena back off the batch, whose table stays on the page. A
        // replay arriving from a hash change is the one route in here that a running batch does not
        // already block, so stopping is not merely tidiness.
        batch.stop()
        batchBoard = null

        // The chrome before the measure, which is the one ordering constraint here. The scrub row
        // comes and goes with replay mode and sits in the board's own column, so measuring first
        // would size the board against a row that is about to arrive — or one that has just left.
        renderChrome()
        renderer.fit(match.view)
        refreshOverlay()

        if (match.interactive) {
            // Play up to the player's first move and stop there: any slot ahead of them in the turn
            // order opens, and then the board sits and waits, which is the whole point. Costs no
            // clock, so it happens whether or not the board is the thing on screen.
            playRound()
            return
        }
        // The page opens on the menu with a match already set up behind it. Starting its clock there
        // would be a game playing itself out where nobody can see it, which is the same thing
        // `navigate` refuses on the way out.
        if (screen == Screen.GAME) {
            scheduler.start()
        }
        renderChrome()
    }

    private fun newMatch(options: MatchOptions) {
        // Start match means "play this", so the form it was read from puts itself away. Only here:
        // a restart or a replay arriving by hash did not come from a panel and has none to close.
        openPanel = null
        level = null
        playFresh(setupFrom(options))
    }

    /**
     * Opens a rung of the ladder, on the game screen, from a seed nobody has played before.
     *
     * The level *is* the configuration, so nothing upstream is consulted: `LadderLevel.setup` seats
     * the player and the opponent on that level's own board and map, and everything downstream is
     * the ordinary match — the same driver, the same renderer, the same codec, so a level is
     * shareable like anything else.
     *
     * **A fresh seed every attempt.** A level that replayed identically after a loss would be a
     * puzzle with one solution rather than a game, and the opponents on the first three rungs draw no
     * randomness at all, so the seed is the only thing that varies for them.
     *
     * Deliberately not routed through [navigate]: the match is what changes, and the screen follows
     * from it. Going the other way would leave a frame in which the level's board had not been built
     * yet.
     */
    private fun startLevel(index: Int) {
        level = index
        screen = Screen.GAME
        openPanel = null
        playFresh(Ladder.levelAt(index).setup(freshSeed(), PlayableRegistry.HUMAN_ID))
    }

    /** Puts a brand-new match on the board, whatever asked for it. Never a recording. */
    private fun playFresh(setup: MatchSetup) {
        scheduler.stop()
        input.clear()
        replay = null
        shareUrl = null
        match = Match(setup, registry)
        begin()
    }

    private fun restart() {
        val again = level
        if (again != null) {
            // On the ladder, "again" is another attempt rather than the same game: see [startLevel].
            startLevel(again)
            return
        }

        scheduler.stop()
        input.clear()
        val record = replay
        match = if (record == null) Match(match.setup, registry) else Match.playback(record)
        begin()
    }

    private fun play() {
        // A finished match has nothing left to play, so Play means "again" — and so does the end of
        // a partial recording, which has no outcome but is just as over. Without the second half,
        // Play on a parked mid-match link starts a scheduler that immediately parks again.
        if (match.outcome != null || (replay != null && awaitingInput)) {
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
        refreshOverlay()
        renderChrome()
    }

    private fun share() {
        shareUrl = replayLink.publish(ReplayCodec.encode(replay ?: match.record()))
        renderChrome()
        chrome.copyShareUrl()
    }

    /**
     * Switches to watching the recording of the match just played — [load], fed from the board
     * instead of the address bar.
     *
     * Guarded here and not only by the button's disabled flag, for the same reason the space bar
     * is: only a *finished* match of the player's own is offered, because a partial recording parks
     * at "end of the recording", which reads as broken rather than as a replay.
     */
    private fun watchReplay() {
        if (replay != null || match.outcome == null) {
            return
        }
        load(match.record())
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
            walls = options.walls,
            budgets = IntArray(options.slots.size) { options.slots[it].budgetPerTurn },
            slotParams = options.slots.map { it.params },
        )

    // -- the batch

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
        // The queue and any route still in it. The arena is about to belong to the tournament, so
        // there is nothing left for either to steer.
        forgetPath()
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
                    format = options.format,
                    walls = options.walls,
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
        refreshOverlay()
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
            refreshOverlay()
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
        batchTable = if (tournament.matchesPlayed == 0) {
            ""
        } else {
            tournament.table.toString() + batchRatings(tournament.table)
        }
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

    /** " — Space vs Wall hugger", or nothing at all in the instant before the first match is seated. */
    private fun seatingText(playing: Match?): String {
        val seated = playing ?: return ""
        val labels = labelsFor(seated)
        return (0 until seated.setup.slotCount).joinToString(separator = " vs ", prefix = " — ") { labels[it] }
    }

    // -- one turn

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
     *
     * **A key mid-drag takes over outright.** The route goes, the moves it queued go with it, and so
     * does the clock it started — otherwise the two ways of saying where to go interleave and the
     * snake does what neither one asked for.
     */
    private fun steer(direction: Direction) {
        endPath()
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
        val result = match.step()
        // Before anything is painted, so the overlay below draws the route as it stands after the
        // move rather than as it stood before one.
        consumePlan(result)

        when (result) {
            is StepResult.Advanced -> {
                awaitingInput = false
                // A fatal move recolours a whole body, which is why the engine reports no dirty
                // cells for it: the snake did not move, it changed what it is.
                if (result.fatal) {
                    renderer.paintSnake(match.view, result.id)
                } else {
                    renderer.paintMove(match.view, result.id, match.events())
                }
                // The board moved under a pointer that did not, so the square being asked about may
                // have changed hands and the highlighted body certainly changed shape.
                refreshOverlay()
            }

            is StepResult.Eliminated -> {
                awaitingInput = false
                renderer.paintSnake(match.view, result.id)
                refreshOverlay()
            }

            StepResult.AwaitingInput -> {
                awaitingInput = true
                // Under playback this is terminal, not a pause. The scripted slots have run off the
                // end of a partial recording and there is no key that could ever resume them, so
                // reporting it as merely awaiting input leaves the scheduler stepping once a frame
                // forever to be told the same thing — while the transport reads "Pause" and says
                // nothing is stopped. Parking is the honest state, and it is the argument
                // `InteractiveBot` makes for playing a fatal move rather than waiting for a key that
                // cannot come.
                return if (replay == null) {
                    TurnScheduler.Progress.AWAITING_INPUT
                } else {
                    TurnScheduler.Progress.FINISHED
                }
            }

            is StepResult.Finished -> return TurnScheduler.Progress.FINISHED
        }

        if (match.outcome == null) {
            return TurnScheduler.Progress.CONTINUED
        }
        // The one moment a level is settled: a verdict has just appeared on the board. Here rather
        // than where the verdict is *shown*, which is derived once a frame and would write the same
        // win down sixty times a second.
        recordLevelWin()
        return TurnScheduler.Progress.FINISHED
    }

    // -- what the chrome is told

    private fun renderChrome() {
        // The one snapshot the whole frame is built from, batch or no batch: whichever match is on
        // screen is the one being reported on, so the scoreboard, the stats and the board agree.
        val shown = batchBoard ?: match
        val faces = facesFor(shown)
        val verdict = resultText()

        chrome.render(
            UiModel(
                screen = screen,
                level = level,
                ladder = progress,
                levelCleared = levelWon(),
                openPanel = openPanel,
                theme = theme,
                result = verdict,
                resultPortrait = if (verdict == null) null else winnerFace(faces),
                replay = replay != null,
                interactive = match.interactive,
                running = scheduler.running,
                turnCount = replay?.turnCount ?: shown.turnIndex,
                status = statusText(shown),
                stats = shown.stats(),
                labels = labelsFor(shown),
                portraits = faces,
                hover = hoverInfo(shown.view, hovered, labelsFor(shown)),
                // The *player's* match, deliberately not `shown`: while a batch owns the board its
                // finished matches must not light the button up.
                canWatchReplay = replay == null && match.outcome != null && !batch.running,
                shareUrl = shareUrl,
                tournament = batchStatus(),
            ),
        )
    }

    /** What [shown]'s seats are called, rebuilt only when the match on screen is a different one. */
    private fun labelsFor(shown: Match): SlotLabels {
        val setup = shown.setup
        if (setup !== labelledSetup) {
            labelledSetup = setup
            labels = SlotLabels(setup, registry)
        }
        return labels
    }

    /** What [shown]'s seats look like, rebuilt when that match changes or the player picks a theme. */
    private fun facesFor(shown: Match): SlotPortraits {
        val setup = shown.setup
        if (setup !== portraitedSetup || theme.id != portraitedTheme) {
            portraitedSetup = setup
            portraitedTheme = theme.id
            slotFaces = SlotPortraits(setup, portraits, theme)
        }
        return slotFaces
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
            // Both ways of moving, because on a phone there is no keyboard to be told about and on a
            // desktop the drag is the one nobody would guess at.
            return if (replay == null) "your move — drag from your head, or the arrow keys" else "end of the recording"
        }
        return if (scheduler.running) "playing" else "paused"
    }

    private fun outcomeText(shown: Match, outcome: MatchOutcome): String = when (outcome.end) {
        MatchEnd.LAST_SNAKE_STANDING ->
            "${labelsFor(shown).of(outcome.winner)} wins — last snake standing"

        MatchEnd.ALL_ELIMINATED -> "nobody left standing"
        MatchEnd.TURN_LIMIT -> "a draw — the turn limit ran out"
    }
}
