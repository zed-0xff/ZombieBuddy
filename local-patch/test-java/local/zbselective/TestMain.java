package local.zbselective;

import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestMain {
    static void check(boolean result, String label) { if (!result) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("unit")) { unit(); return; }
        if (args[0].equals("core")) {
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem");
            Object instance = zfs.getField("instance").get(null);
            zfs.getMethod("loadMods", List.class).invoke(instance, new ArrayList<String>());
            Field first = RuntimeState.class.getDeclaredField("firstBatch"); first.setAccessible(true);
            check(!(Boolean) first.get(null), "real ZFS List method reaches patched Loader");
            System.out.println("GAME VERSION " + zombie.core.Core.getInstance().getGameVersion());
            System.out.println("PASS core"); return;
        }
        Path jar = Path.of(args[1]).toRealPath();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Field instrumentation = loader.getDeclaredField("g_instrumentation"); instrumentation.setAccessible(true);
        instrumentation.set(null, TestAgent.instrumentation);
        Class<?> status = Class.forName("me.zed_0xff.zombie_buddy.Loader$JavaModLoadState");
        Class<?> flags = Class.forName("me.zed_0xff.zombie_buddy.ModFlags");
        Constructor<?> ctor = status.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Field states = loader.getDeclaredField("g_jarLoadStatus"); states.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Path,Object> map = (Map<Path,Object>) states.get(null);
        map.put(jar, ctor.newInstance("Sample", jar, flags.getField("EMPTY").get(null), "loaded", hash, true));
        Class<?> phase = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        @SuppressWarnings({"unchecked","rawtypes"}) Object main = Enum.valueOf((Class) phase, "MAIN");
        Method loadJar = loader.getDeclaredMethod("loadJar", Path.class, String.class, String.class, phase); loadJar.setAccessible(true);
        Field modSet = loader.getDeclaredField("g_curr_modSetID"); modSet.setAccessible(true);
        if (args[0].equals("prepare-save")) modSet.set(null, "SaveProfile");
        if (args[0].equals("resumed-loaded")) check(fixture.target.Target.value() == 4, "original target loaded before patch");
        if (args[0].equals("reenabled")) {
            RuntimeState.begin(new ArrayList<>());
            RuntimeState.finish();
        }
        RuntimeState.begin(new ArrayList<>(List.of("Sample")));
        boolean loaded = (Boolean) loadJar.invoke(null, jar, "fixture.sample", hash, main);
        if (args[0].startsWith("prepare") || args[0].equals("updated") || args[0].equals("reenabled")) {
            check(!loaded, "new/updated jar must be deferred");
            check(System.getProperty("fixture.executed") == null, "Main must not execute before restart");
            // No UI/system-exit in this assertion process. Persist the real candidate set for next JVM.
            Field selected = RuntimeState.class.getDeclaredField("selected"); selected.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,RuntimeState.Entry> rows = (Map<String,RuntimeState.Entry>) selected.get(null);
            RuntimeState.write(Path.of(System.getProperty("zbselective.state")), rows);
        } else if (args[0].startsWith("resumed")) {
            check(loaded, "prepared jar loads after restart");
            check("yes".equals(System.getProperty("fixture.executed")), "Main executes only on resumed JVM");
            check(fixture.target.Target.value() == 73, "real ByteBuddy target hook applied");
            check(fixture.target.Delegated.value() == 91, "MethodDelegation preserved");
            check(fixture.target.RegexTarget.value() == 74, "regex class target preserved without callback class circularity");
            check(me.zed_0xff.zombie_buddy.Exposer.getExposedClasses().stream().anyMatch(c -> c.getName().equals("fixture.sample.SampleApi")), "LuaClass preserved");
            check(me.zed_0xff.zombie_buddy.Exposer.getClassesWithGlobalLuaMethod().stream().anyMatch(c -> c.getName().equals("fixture.sample.SampleApi")), "global LuaMethod preserved");
            RuntimeState.finish();
        } else if (args[0].equals("disabled")) {
            check(loaded, "prepared mod initially loads");
            RuntimeState.begin(new ArrayList<>());
            check(RuntimeState.enabledPreloadIds(List.of("Sample")).isEmpty(), "disabled preload filtered");
            RuntimeState.finish(); // Separate JVM must exit 42 before returning.
            throw new AssertionError("disabled hook must force restart");
        } else if (args[0].equals("profile")) {
            check(!loaded, "default menu skips MAINs for pending save profile");
            check(System.getProperty("fixture.executed") == null, "menu cannot execute skipped Main");
            RuntimeState.finish();
            check(RuntimeState.read(Path.of(System.getProperty("zbselective.state"))).size() == 1, "menu preserves save plan");
            modSet.set(null, "SaveProfile");
            RuntimeState.begin(new ArrayList<>(List.of("Sample")));
            check((Boolean) loadJar.invoke(null, jar, "fixture.sample", hash, main), "save loads prepared selection");
            check(fixture.target.Target.value() == 73, "save target hook still active");
            RuntimeState.finish();
        }
        System.out.println("PASS " + args[0]);
    }

    static void unit() throws Exception {
        var targets = new TargetInstrumentation.Targets(Set.of("zombie.iso.IsoWorld"));
        AtomicInteger visits = new AtomicInteger();
        ClassFileTransformer original = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader cl, String n, Class<?> c, java.security.ProtectionDomain d, byte[] b) {
                visits.incrementAndGet(); return b;
            }
        };
        var filtered = TargetInstrumentation.filter(original, targets);
        check(filtered.transform(null,"java/lang/String",null,null,new byte[1]) == null, "unrelated class skipped");
        check(filtered.transform(null,"zombie/iso/IsoWorld",null,null,new byte[1]) != null, "exact target forwarded");
        check(visits.get() == 1, "no unrelated ByteBuddy call");
        for (String pattern : List.of("*", "zombie.*", "*.IsoWorld", "*iso*", "/zombie\\..*/"))
            check(new TargetInstrumentation.Targets(Set.of(pattern)).matches("zombie/iso/IsoWorld"), "pattern preserved " + pattern);
        check(!new TargetInstrumentation.Targets(Set.of("zombie.vehicles.*")).matches("zombie/iso/IsoWorld"), "prefix excludes others");
        check(targets.matches(null), "anonymous VM name preserved");
        Path dir = Files.createTempDirectory("zbselective-test-");
        Path file = dir.resolve("state.properties");
        var row = new RuntimeState.Entry("中文模组", "C:\\模组\\x.jar", "sample", "abc");
        RuntimeState.write(file, Map.of(row.path(), row));
        check(RuntimeState.read(file).get(row.path()).equals(row), "Unicode cache round trip");
        Path mods = dir.resolve("default.txt");
        Files.writeString(mods, "mods\n{\n mod = Sample,\n mod = 中文模组,\n}\n");
        check(RuntimeState.readDefaultIds(mods).equals(Set.of("Sample","中文模组")), "default selection parser");
        RuntimeState.begin(new ArrayList<>(List.of("Sample")));
        check(RuntimeState.enabledPreloadIds(List.of("Sample","Disabled")).equals(List.of("Sample")), "only enabled preload IDs");
        System.out.println("PASS unit");
    }
}
