# Approval regression checks

`ApprovalChoicesTest` and `JarBatchApprovalProtocolTest` run with the normal `unitTest` task.
The dialog test calls only `ApprovalDialogProbe.stateTests()`: this exercises Java state and
response generation without creating an ImGui/native graphics context. Unit tests need the
game Java classes, but do not need native ImGui libraries or draw/mouse support.

`ApprovalDialogProbe.main` is an explicit integration check. It also exercises native ImGui
mouse events and draw vertices, the packaged loader/store, and a separate JVM restart.
It has no JUnit annotations, does not match Gradle's default test-class discovery patterns,
and is not invoked by the ordinary unit-test task. Keep native checks in this explicit path.

After building `shadowJar` and `testClasses`, run the probe explicitly with the shaded agent
first on the classpath, followed by compiled test classes and the game JAR. Supply a disposable
output directory as its sole argument. JVM options are `-Xverify:all`,
`--enable-native-access=ALL-UNNAMED`, and `-Djava.library.path=<game directory>`.
The native library must match the host OS/architecture. This check starts no game or OpenGL
window; its native draw/mouse assumptions still make it an integration test. It does not
establish in-game layout, focus, or client/Coop/dedicated acceptance.

`ApprovalPersistenceProbe.main` is a separate, non-graphics integration check. It runs the real
`Loader.loadMods()` path with isolated Vanilla mod metadata, the actual dialog response/JSON
protocol and the original dirty-bit save gate. Only initial fixture setup writes the store
directly; there is no manual save after a reset. It covers sole saved Allow/Deny resets,
preservation of unrelated records, Save off, Cancel, missing hashes, blocked resets and untouched
rows. Fresh child JVMs verify that cleared decisions are requested again without an implicit
denial being written back. HTTP is replaced with local fixtures; no game/server is started.
Run it explicitly with the same classpath and disposable output argument, using `-Xverify:all`;
native-access/library-path options are not needed. The focused JUnit tests separately check
change reporting and the loader's dirty flag, including preserving an already dirty state.
