# :ui

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/UI.md`](../docs/UI.md) before changing anything here.

The UI guide documents the one-way data flow, frame-versus-turn cadence, three clocks, board fitting,
and two canvases. The overlay carries **the snakes themselves** and is painted whole, so
`paintOverlay` must follow every played turn and every `fit`. Its order is
**wash → preview → route → bodies → heads**. The board canvas holds the background, walls, and grid;
`fit` is the only operation that paints it.

**Every press on the board costs the player a move.** There is no cancel. Nothing in Kotlin prevents
a stray click while another UI layer is open; `#panel-scrim` and `#dialog-result` are full-viewport
and above `#board`. Changing their stacking or box can change the game.

`SteerPad` is the arrow keys for a device without them. It is out of flow inside `.board-wrap`, which
`BoardRenderer.fit` measures. Putting it in flow makes the board reserve space for the pad. Position
it through `GameSession.fitBoard`, the single fitting entry point.

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
