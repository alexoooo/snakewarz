# :app

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/UI.md`](../docs/UI.md) before changing anything
here. The page shell, boot path, and GitHub Pages deployment are all documented there.

`main()` is under fifty lines of wiring and that is the point: it injects `ShippedBots` into a
session that knows only the `BotRegistry` interface, routes `#r=` replays, and answers `Portraits`
from `resources/portrait/`—one `.svg` per shipped slug. `:ui` asks by slug because it may not see a
bot class. A slug with no file gets a drawn identicon rather than a failure, so `PortraitUrlTest`
detects drift between the registry and resource directory.

`document.body.classList.add("booted")` must stay ahead of `session.start()`: a hidden element reports
`clientWidth == 0`, so revealing `#app` late sizes every board to the minimum cell. The static
skeleton in `index.html` is looked up by id and only written to. Do not construct structure in Kotlin,
apart from the two documented exceptions that come from `BotRegistry.entries`.
