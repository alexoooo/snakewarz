// Root build file holds no logic. Shared configuration lives in build-logic convention
// plugins (snakewarz.pure, snakewarz.browser) so that module build files stay ~10 lines.

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}
