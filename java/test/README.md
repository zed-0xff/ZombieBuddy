# Approval regression checks

`AuthorTrustSeparationTest` runs genuine Ed25519-signed fixture mods through the shaded
agent, the real ImGui state/JSON boundary, Console input and original loader save gates.
It checks unchanged direct selections/approval bytes, independent explicit hash choices,
author-only preload registrations for an entire accepted batch, later signed mods in the
same process, and two actual-agent JVM restarts distinguishing saved/session trust.
Additional cases cover unsigned/invalid/banned authors, EOF and malformed direct metadata.
No game or graphics window starts. The test launches the platform's Java executable and
uses the configured game classpath; native Linux/game acceptance remains separate.

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

`AuthorUntrustProbe.main` checks the separate author-untrust-only response through
the real dialog, JSON readers, loader and config/hash-store save gates. It covers
initial undecided/Allow/Deny rows, Save off, Cancel, explicit Deny/reset, preload
preservation and marker eligibility. Fresh JVMs verify that untrust survives while
a new hash is prompted again and approval bytes are unchanged. Signature metadata
in this UI fixture is synthetic; this is not a cryptographic-verification test.
All network responses are fixtures; no game or UI window starts. Use the same
disposable-output/classpath pattern with `-Xverify:all`. `AuthorUntrustTest` runs
only the state matrix as an ordinary unit test.

`ApprovalCancelTest` runs the non-graphics Cancel state matrix with normal JUnit. It checks saved
Allow/Deny, current verified-author trust, discarded window edits, fresh saved state after dialog
creation, session-only choices, signature/ban limits and unchanged preload/configuration. Shared
loader/config state is restored afterward. Additional tests pin the private nonsticky IPC marker,
its real JSON round trip, no decision-file creation, and unchanged ordinary confirmation/reset.

`ApprovalCancelProbe.main` is the explicit packaged integration entry. It adds the actual
`Loader.loadMods()` save gates and six fresh JVM restarts to that state matrix,
covering Save on/off for saved Allow, saved Deny and unresolved hashes. Run against the shaded
agent first, followed by compiled test classes and game JAR, using `-Xverify:all` and a disposable
output directory. No ImGui native graphics context or game/server starts. Author/signature
metadata in the state matrix is synthetic; the unsigned actual-loader fixtures do not claim new
cryptographic validation. Cancel resolution runs in the loading process after JSON IPC.

The probe also supplies a controlled banned Workshop response to the real parser and loader.
At frontend entry it checks that automatic author-trust revocation is already on disk, before
creating the dialog. Eight cases combine Save on/off, unresolved/saved-Deny sibling hashes and
ordinary/deliberately stale disk trust. Unrelated trust and explicit saved Allow remain effective.
This needs no actual banned Workshop download. The restart launcher selects `java.exe` on
Windows and `java` on other operating systems; actual platform execution remains separate.

The JSON reader tests reject explicit null flags in both request/response readers and in a later
row following a valid reset. The IPC-only null-safe adapter leaves the persistent-store adapter
unchanged; an incomplete message is rejected before any reset or author change is applied.
## Allow-all preloads

`AllowAllPreloadTest` runs through the shaded agent in ordinary `unitTest`, separately for client and dedicated game roles. It verifies automatic registration without author trust for declared signed/unsigned/invalid-sidecar preloads, existing explicit Deny and hash-persistence behavior, missing declarations, Steam bans during normal and early loading, and fresh-JVM `PreMain` execution. A console warning names new/updated entries and requires a full restart while the current process continues. No automatic exit/restart is implemented.

The probe uses controlled HTTP responses and the actual game role flag, not a running game/server. Each accepted preload case starts a fresh agent JVM with an empty bootstrap config, reloads the prepared configuration after installing offline responses, and invokes the actual early loader. Native Linux/macOS execution remains unconfirmed.
