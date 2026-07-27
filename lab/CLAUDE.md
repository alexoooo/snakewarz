# :lab

Read [`../docs/Workflow.md`](../docs/Workflow.md) before changing anything here.

This is a measuring instrument: it sits above everything and under nothing, and it is the one place
besides `:ui` where a clock and a `println` are allowed. Nothing may depend on it, and it may not see
`:ui` or `:app`.

Entrant parsing is **strict** on purpose — a mistyped knob name would otherwise quietly measure the
default and waste however many minutes the batch takes.
