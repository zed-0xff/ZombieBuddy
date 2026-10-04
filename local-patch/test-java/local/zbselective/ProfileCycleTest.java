package local.zbselective;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

public final class ProfileCycleTest {
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path sample = Path.of(args[1]).toRealPath(), menu = Path.of(args[2]).toRealPath();
        String sampleHash = hash(sample), menuHash = hash(menu);
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Field profile = loader.getDeclaredField("g_curr_modSetID"); profile.setAccessible(true);
        Field statuses = loader.getDeclaredField("g_jarLoadStatus"); statuses.setAccessible(true);
        Class<?> status = Class.forName("me.zed_0xff.zombie_buddy.Loader$JavaModLoadState");
        Constructor<?> ctor = status.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Class<?> flags = Class.forName("me.zed_0xff.zombie_buddy.ModFlags");
        Object active = flags.getMethod("with",int.class).invoke(flags.getField("EMPTY").get(null),flags.getField("MF_ACTIVE").getInt(null));
        @SuppressWarnings("unchecked") Map<Path,Object> states = (Map<Path,Object>) statuses.get(null);
        states.put(sample, ctor.newInstance("Sample",sample,flags.getField("EMPTY").get(null),"loaded",sampleHash,true));
        states.put(menu, ctor.newInstance("MenuOnly",menu,active,"loaded",menuHash,true));
        Class<?> phase = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        @SuppressWarnings({"unchecked","rawtypes"}) Object main = Enum.valueOf((Class)phase,"MAIN");
        Method load = loader.getDeclaredMethod("loadJar",Path.class,String.class,String.class,phase); load.setAccessible(true);
        Path stateFile = Path.of(System.getProperty("zbselective.state"));

        profile.set(null,"default"); RuntimeState.begin(List.of("Sample"));
        boolean defaultLoaded = (Boolean)load.invoke(null,sample,"fixture.sample",sampleHash,main);
        check(defaultLoaded == mode.equals("default-plan"), "startup default follows its planned Java environment");
        RuntimeState.finish();
        profile.set(null,"currentGame"); RuntimeState.begin(List.of("Sample"));
        load.invoke(null,sample,"fixture.sample",sampleHash,main);
        RuntimeState.finish();
        check("yes".equals(System.getProperty("fixture.executed")), "selected save Java Main executed");
        check(fixture.target.Target.value()==73, "save hook active");
        byte[] gamePlan = Files.readAllBytes(stateFile);

        // Returning to a different menu list, then reloading the menu, must not restart or alter the game plan.
        for (int i=0; i<3; i++) {
            profile.set(null,"default"); RuntimeState.begin(i==0 ? List.of("MenuOnly") : List.of("Sample","MenuOnly"));
            check(!(Boolean)load.invoke(null,menu,"fixture.menu",menuHash,main), "new menu Java code remains deferred");
            Method statusFlags = status.getDeclaredMethod("flags"); statusFlags.setAccessible(true);
            Method statusReason = status.getDeclaredMethod("reason"); statusReason.setAccessible(true);
            check(!(Boolean)flags.getMethod("has",int.class).invoke(statusFlags.invoke(states.get(menu)),flags.getField("MF_ACTIVE").getInt(null)), "deferred menu code is not active, including stale flags");
            check(statusReason.invoke(states.get(menu)).toString().startsWith("deferred"), "menu reports its deferred status");
            check((Boolean)flags.getMethod("has",int.class).invoke(statusFlags.invoke(states.get(sample)),flags.getField("MF_ACTIVE").getInt(null)), "installed save code remains active");
            Method activeMods = loader.getDeclaredMethod("getActiveJavaMods"); activeMods.setAccessible(true);
            check(!((List<?>)activeMods.invoke(null)).contains(states.get(menu)), "active API excludes deferred menu code");
            if (i>0) check(RuntimeState.allowJar(sample,"fixture.sample",sampleHash,"MAIN"), "already loaded shared code available to menu");
            RuntimeState.finish();
            check(Arrays.equals(gamePlan,Files.readAllBytes(stateFile)), "menu return never overwrites save plan");
            check(System.getProperty("fixture.menu.executed")==null, "deferred menu Main did not execute");
        }

        profile.set(null,"currentGame"); RuntimeState.begin(List.of("Sample"));
        check(RuntimeState.allowJar(sample,"fixture.sample",sampleHash,"MAIN"), "same save remains eligible after menu returns");
        RuntimeState.finish();
        if (mode.equals("new-code")) {
            profile.set(null,"currentGame"); RuntimeState.begin(List.of("Sample","MenuOnly"));
            check(RuntimeState.allowJar(sample,"fixture.sample",sampleHash,"MAIN"), "shared save code retained");
            check(!(Boolean)load.invoke(null,menu,"fixture.menu",menuHash,main), "new game Java code still deferred");
            RuntimeState.finish(); // Must exit 42; menu deferral cannot grant authorization or eligibility.
            throw new AssertionError("new game Java selection must require restart");
        }
        if (mode.equals("removed-code")) {
            profile.set(null,"currentGame"); RuntimeState.begin(List.of());
            RuntimeState.finish(); // Must exit 42 for an actual new game without the already installed hook.
            throw new AssertionError("changed game selection must require restart");
        }
        System.out.println("PASS profile cycle " + mode);
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
