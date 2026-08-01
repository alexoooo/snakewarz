// This suite contains one synchronous Chrome cost sweep across six boards. Kotlin/Wasm does not
// yield back to Karma while a test method is running, so the ordinary two-second unit-test timeout
// would report a healthy measurement as a disconnected browser.
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
