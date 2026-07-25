plugins {
    id("snakewarz.pure")
}

// :core has no project dependencies, ever — not even :bot-api. The engine does not know that
// bots exist. If you are about to add a dependency here, you are about to break the design.
//
// This is enforced by :core:checkModulePurity, which runs as part of `check`.
