# :ui

Read [`../docs/UI.md`](../docs/UI.md) before changing anything here.

It carries the one-way data flow and the frame-versus-turn cadence, the three clocks, the fixed board
extent, and the overlay canvas — which is painted whole, so `paintOverlay` has to follow every
`paintMove`, `paintSnake` and `repaint` or every decoration vanishes the frame a batch repaints.

`GameSession` and `ReplayLink` are the only public declarations; everything else stays `internal`.
Nothing here may reference `:bots` — the renderer cannot tell a wall hugger from a human, and
`checkModulePurity` fails the build on it.
