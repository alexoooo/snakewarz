# :ui

Read [`../docs/UI.md`](../docs/UI.md) before changing anything here.

It carries the one-way data flow and the frame-versus-turn cadence, the three clocks, the board that
fills its frame, and the overlay canvas — which is painted whole, so `paintOverlay` has to follow
every `paintMove`, `paintSnake` and `repaint` or every decoration vanishes the frame a batch repaints.
There are three decorations on it now and their order is **wash → route → threads**.

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
[CC-06](../docs/Coding-Standards.md#cc-06--a-package-is-a-handful-of-files) pressure here — `ladder/`
was nested out of it rather than a fifteenth file being added. Split along **what reads what** before
adding another: the next one belongs in a sub-package with its consumers, not beside them.
