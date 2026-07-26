// Mocha's two-second default is a unit-test budget, and `BotLadderTest` is not a unit test: it
// plays two hundred complete matches with a search bot in each of them, on purpose, because a
// strength claim made over five games is not a claim. That takes a couple of seconds on the JVM and
// two to three times that in wasm, and timing out on it would say nothing about the code.
//
// Raised here rather than by shrinking the sample, because the sample size is the point.
config.set({
    client: {
        mocha: {
            timeout: 120000
        }
    }
});
