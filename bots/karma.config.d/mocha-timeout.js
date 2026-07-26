// Mocha's two-second default is a unit-test budget, and `BotLadderTest` is not a unit test: it
// plays hundreds of complete matches with a search bot in each of them, on purpose, because a
// strength claim made over five games is not a claim. That takes a minute or two on the JVM and two
// to three times that in wasm, and timing out on it would say nothing about the code.
//
// Raised here rather than by shrinking the sample, because the sample size is the point.
//
// Karma's own timeouts have to move with it, and they are the ones that bite first. A test method is
// one synchronous call into wasm, so nothing -- not the reporter, not Karma's own heartbeat -- gets
// a turn on the event loop until it returns; the browser then looks hung rather than busy, and Karma
// drops a browser that is working perfectly. That failure reports as "reconnect failed before
// timeout of 2000ms (ping timeout)" and says nothing whatever about the test.
config.set({
    client: {
        mocha: {
            timeout: 600000
        }
    },
    pingTimeout: 600000,
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
    browserDisconnectTolerance: 2,
    captureTimeout: 120000
});
