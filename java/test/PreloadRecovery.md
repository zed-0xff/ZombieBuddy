# Preload registration regressions

Run `gradle unitTest --tests me.zed_0xff.zombie_buddy.PreloadRecoveryTest
-PgameClasspath=<projectzomboid.jar>` with JDK 25 and a Build 42 game JAR.
Use the Gradle version selected by the project, or an equivalent installed Gradle.

The six JUnit tests launch disposable child JVMs with `-Xverify:all`, temporary
configuration directories and a real ephemeral Ed25519-signed JAR. Workshop and
Steam-profile responses use the existing HTTP response cache; no remote service
or private signing key is needed. The fixture exercises actual `Loader.loadMods`,
signature checks and the ordinary config dirty/save gate. A second JVM verifies
that the saved registration executes `PreMain` through `Loader.preloadMods`.

Coverage includes saved Allow without author trust (`prompt` and `deny-new`),
verified author trust without a saved hash (Workshop and local mods), unchanged existing registrations,
unrelated approvals/options, and exclusion of Deny, invalid/unsigned/banned mods,
missing preload declarations, session-only choices and unverified `allow-all`.
A forced current batch omits its preload flag while retaining a previous saved
Allow, to ensure recovery does not undo the frontend's current decision.

The current master already has separate frontend persistence behavior; these
tests do not claim to fix Save/Cancel handling addressed by PR #92. They establish
loader and isolated restart behavior, not a graphical game boot, native Linux
execution, or multiplayer acceptance.

Approved moved JARs now update obsolete registrations after those same checks;
valid existing registrations, including filesystem aliases of the same files,
remain unchanged. See [selection and early-load regressions](PreloadSelection.md)
for the additional actual-agent/Vanilla-save coverage.
