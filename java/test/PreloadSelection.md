# Saved preload selection and early execution regressions

Run `gradle unitTest --tests me.zed_0xff.zombie_buddy.PreloadSelectionTest
--tests me.zed_0xff.zombie_buddy.PreloadRecoveryTest
-PgameClasspath=<projectzomboid.jar>` with JDK 25. The task builds the actual
shaded agent and launches isolated JVMs with it. The test agent property uses
the same `zb.test.agentJar` name as PR #63.

Seven new cases use actual installed Vanilla classes, the ModSelector's real
`LuaManager.GlobalObject.saveModsFile()` entry, the packaged annotation pipeline,
temporary game/config paths, real ephemeral Ed25519 signatures and saved Allow.
Profile responses are offline fixtures; private keys are generated only in the
child JVM. Active/valid/signed state is checked before the late/early assertions.

- Successful saves remove stale entries immediately, including an empty list and
  switches between two mods sharing a Java package. Inactive Java code cannot
  override the selected WIP code during subsequent normal loading.
- A failed Vanilla write leaves preload configuration and approvals unchanged.
- The first default load repairs stale entries without a preceding in-process
  default snapshot. `currentGame` does not erase global default registrations.
- A pre-existing registration without actual early execution displays a console
  and Watermark restart notice. A matching actual `PreMain` stays quiet.
- An approved moved JAR updates the selected registration and needs a restart.
  Identity checks preserve aliases of the same existing files.
- Repeated loading does not duplicate console restart notices. New/updated
  registrations keep their existing hint; the console addition already in PR
  #92 is not reimplemented here.

This contribution does not port PR #63's author cache/Coop ownership or PR #92's
author choices, frontend persistence, allow-all registration or Steam-ban gate.
Mutation still passes through `PreloadMods.add/remove` and `Config.save`, so their
read-only guards remain authoritative when combined. A separate combined-only
probe uses #63's profile-fetch seam and exercises the actual save in a read-only
Coop child alongside the existing approval/Cancel/author/policy tests.

Unchanged master fails the save, first-default-load, registered-but-late and
moved-registration cases. Failed-write, context isolation and actual-early cases
pass there. These automated source tests are separate from downstream normal
Extensions game acceptance; no graphical boot or native Linux run of this
upstream source candidate is claimed.
