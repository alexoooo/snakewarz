# :ui

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/UI.md`](../docs/UI.md) before changing anything here.

The UI guide documents the one-way data flow, frame-versus-turn cadence, five clocks, board fitting,
and two canvases. The overlay carries **the snakes themselves** and is painted whole, so
`paintOverlay` must follow every played turn and every `fit`. Its order is
**wash → preview → route → bodies → heads**. The board canvas holds the background, walls, and grid;
`fit` is the only operation that paints it.

**Every accepted press on the board costs the player a move.** There is no cancel. The panel scrim,
result dialog, and Gauntlet intro are full-viewport and above `#board`; `Shell` also makes everything
behind the active layer inert. Changing their stacking or box can change the game.

When that press ends, discard its remaining route and finish the opponents' outstanding turns before
parking on the player again. A pointer gesture must not carry an AI turn into the next gesture; this
is the resting-state invariant it shares with arrow/WASD input.

While a press is held, expose at most one match turn per browser frame. Multiple turns inside one
animation callback paint only their final position and make a snake appear to move twice; bot matches
and replays retain `TurnScheduler`'s multi-turn catch-up loop.

Direct arrow, WASD, and D-pad inputs must not replace an unfinished snake glide. `AnimatedSteering`
keeps rapid directions in order and releases one only after the renderer reports that the preceding
move transition has finished; ownership changes cancel that queue with the other controls.

`SteerPad` is always present for a human match or its replay and disabled while steering is not
available, so a verdict or playback cannot shift the arena. It occupies its own `.arena` grid track:
below the board in portrait and to its right in landscape. The Gauntlet rival occupies the matching
left track. Neither control may be positioned over `.board-wrap` or allowed to clip the canvases.

The keyboard map is recorded in exactly two user-facing places: the table in `docs/UI.md` and the
Keys note in `#panel-settings`. Change `Chrome.onKeyDown`, that note, and that table together.

`GameSession`, `ReplayLink`, and `Portraits` are the only public declarations. Everything else stays
`internal`. The latter two are seams filled by `:app`, because replay destinations and deployed bot
portraits are application concerns.

Nothing here may reference `:bots`. The UI paints a `BoardView` and identifies slots through
interfaces and slugs; an unknown portrait slug gets a generated mark.

`model/` is under [CC-06](../docs/Coding-Standards.md#cc-06--a-package-is-a-handful-of-files)
pressure. Split along **what reads what** before adding another file: put the next concept in a
sub-package with its consumers.
