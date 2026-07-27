# :match

Read [`../docs/Match.md`](../docs/Match.md) before changing anything here.

It carries where the human seat lives and why it is composed outside `ShippedBots`, why a match with
a person in it runs no clock, and why `MatchStats` is derived rather than accumulated — add a counter
to `Match` for a statistic and the scoreboard grows a second source of truth that can disagree.

This module has never seen a bot class and must not: it resolves bots through the `BotRegistry`
*interface*, and `:app` injects the implementation. That is what keeps the replay codec free of bot
classes, and `checkModulePurity` fails the build on it.
