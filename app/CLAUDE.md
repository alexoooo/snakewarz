# :app

Read [`../docs/UI.md`](../docs/UI.md) before changing anything here — the page shell, the boot path
and the GitHub Pages deployment are all in it.

`main()` is under fifty lines of wiring and that is the point: it injects `ShippedBots` into a session that
knows only the `BotRegistry` interface, routes `#r=` replays, and answers `Portraits` out of
`resources/portrait/` — one `.svg` per shipped slug, which `:ui` asks for by slug because it may not
see a bot class. A slug with no file is a drawn identicon rather than a failure, so `PortraitUrlTest`
is what still tells somebody the registry and the directory have drifted.

`document.body.classList.add("booted")` must stay ahead of `session.start()`: a hidden element
reports `clientWidth == 0`, so revealing `#app` late sizes every board to the minimum cell. The
static skeleton in `index.html` is looked up by id and only ever written to — do not start
constructing structure in Kotlin, with the two documented exceptions that come off
`BotRegistry.entries`.
