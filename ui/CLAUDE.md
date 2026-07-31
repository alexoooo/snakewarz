# :ui

Read [`../docs/UI.md`](../docs/UI.md) before changing anything here.

It carries the one-way data flow and the frame-versus-turn cadence, the three clocks, the board that
fills its frame, and the overlay canvas — which carries **the snakes themselves** and is painted
whole, so `paintOverlay` has to follow every turn played and every `fit` or the board has no game on
it. The board canvas is background, walls and gridlines, and `fit` is the only thing that paints it.
The overlay's order is **wash → preview → route → bodies → heads**.

**Every press on the board costs the player a move**, and there is no cancel: a press takes hold and
plays one step wherever a route to it exists, which is what the hover preview is there to show before
you commit. Nothing in Kotlin stops a stray click — the pointer has no equivalent of
`Shell.boardHasKeys`. What stands between one and a lost snake is CSS: `#panel-scrim` and
`#dialog-result` are full-viewport and above `#board`, so no press reaches the canvas while either is
up. Change their stacking or their box and you have changed the game.

`SteerPad` is the arrow keys for a device that has none, and it is positioned **out of flow inside
`.board-wrap`** — the container `BoardRenderer.fit` measures. Put it in the flow and the board is
sized against room the pad has taken. It is placed from `GameSession.fitBoard`, which is the one door
every fit goes through, because the pad hangs off the drawn board's own edges.

The keyboard map is written down in exactly two places — `docs/UI.md`'s table and the Keys note in
`#panel-settings` — so `Chrome.onKeyDown`, that note and that table change together. A shortcut list
that has stopped matching what the keys do is worse than no list.

`GameSession`, `ReplayLink` and `Portraits` are the only public declarations; everything else stays
`internal`. The last two are seams `:app` fills, for the same reason: where a replay link goes and
where a bot's picture comes from are both facts about what is deployed around the page.

Nothing here may reference `:bots` — the renderer cannot tell a wall hugger from a human, and
`checkModulePurity` fails the build on it. That is why a portrait is asked for by **slug** and why an
unknown one falls back to a drawn mark rather than to a name in this module.

`model/` is at fourteen files and is the package under
[CC-06](../docs/Coding-Standards.md#cc-06--a-package-is-a-handful-of-files) pressure here —
`gauntlet/` was nested out of it rather than a fifteenth file being added. Split along **what reads what** before
adding another: the next one belongs in a sub-package with its consumers, not beside them.
