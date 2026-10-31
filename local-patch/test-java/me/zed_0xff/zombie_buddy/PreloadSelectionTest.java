package me.zed_0xff.zombie_buddy;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import local.zbselective.RuntimeState;

public final class PreloadSelectionTest {
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path jar = Path.of(args[1]).toRealPath();
        String hash = Utils.sha256Hex(jar);
        if (mode.equals("prepare")) {
            Field statuses = Loader.class.getDeclaredField("g_jarLoadStatus"); statuses.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Path,Loader.JavaModLoadState> rows = (Map<Path,Loader.JavaModLoadState>)statuses.get(null);
            rows.put(jar,new Loader.JavaModLoadState("SaveOnly",jar,ModFlags.EMPTY,"approved",hash,true));
            Field profile = Loader.class.getDeclaredField("g_curr_modSetID"); profile.setAccessible(true); profile.set(null,"currentGame");
            RuntimeState.begin(List.of("SaveOnly"));
            check(!Loader.loadJar(jar,"fixture.preload",hash,Loader.Phase.MAIN), "new code deferred");
            Field selected = RuntimeState.class.getDeclaredField("selected"); selected.setAccessible(true);
            Method write = RuntimeState.class.getDeclaredMethod("write",Path.class,Map.class); write.setAccessible(true);
            write.invoke(null,Path.of(System.getProperty("zbselective.state")),selected.get(null));
            check(Loader.getActiveJavaMods().isEmpty(), "approved but uninstalled code is not active");
        } else {
            boolean expected = mode.equals("save");
            check(expected == "yes".equals(System.getProperty("fixture.preload.executed")), "save-specific premain runs only for the prepared unchanged selection");
            check(expected == RuntimeState.isInstalled(jar), "installed status matches actual premain");
            check(expected == Loader.getActiveJavaMods().stream().anyMatch(s -> s.id().equals("SaveOnly")), "active API matches actual preload");
            check(!RuntimeState.allowJar(jar,"fixture.preload","incorrect-hash","PREMAIN"), "hash mismatch remains rejected");
            check(!RuntimeState.allowJar(jar,"wrong.package",hash,"PREMAIN"), "package mismatch remains rejected");
            if (mode.equals("default")) check(!RuntimeState.shouldPreload("SaveOnly",jar), "default boot still honors menu selection");
        }
        System.out.println("PASS preload selection " + mode);
    }
}
