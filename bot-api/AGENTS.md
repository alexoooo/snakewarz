# :bot-api

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/Bots.md`](../docs/Bots.md) before changing anything
here.

This is the contract bot authors read, so it is small and stable on purpose. `Bot.chooseMove` is
synchronous and must never become `suspend`: bots run the engine inside their own turn, and a
suspending bot cannot serve as another bot's rollout policy without `runBlocking`, which does not
exist in wasm.

A knob's name and a `Choice` value are frozen once released because both travel in replay URLs.
