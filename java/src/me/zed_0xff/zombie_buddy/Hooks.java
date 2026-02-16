package me.zed_0xff.zombie_buddy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Hooks {
    private Hooks() {}

    public interface OnGameInitComplete {
        void onGameInitComplete();
    }

    private static final List<OnGameInitComplete> gameInitCompleteHooks =
            new CopyOnWriteArrayList<>();

    /**
     * Returns true if the given hook name is recognized.
     */
    public static boolean isKnownHook(String name) {
        return "onGameInitComplete".equals(name);
    }

    /**
     * Generic hook registration by name. For now only supports "onGameInitComplete".
     */
    public static void register(String name, Runnable hook) {
        if (hook == null || name == null) {
            return;
        }
        if (!isKnownHook(name)) {
            Logger.error("Hooks.register: unknown hook name: " + name);
            return;
        }
        if ("onGameInitComplete".equals(name)) {
            gameInitCompleteHooks.add(() -> hook.run());
        }
    }

    private static final List<String> alreadyRanHooks = new CopyOnWriteArrayList<>();
    private static boolean isHookAlreadyRan(String name) {
        return alreadyRanHooks.contains(name);
    }
    private static void setHookAlreadyRan(String name) {
        alreadyRanHooks.add(name);
    }

    /**
     * Run all hooks registered under the given name.
     * For now only supports "onGameInitComplete".
     */
    public static void run(String name) {
        if (!isKnownHook(name)) {
            Logger.error("Hooks.run: unknown hook name: " + name);
            return;
        }
        if (isHookAlreadyRan(name)) {
            return;
        }
        setHookAlreadyRan(name);
        Logger.info("Running hooks for: " + name);
        for (OnGameInitComplete hook : gameInitCompleteHooks) {
            try {
                hook.onGameInitComplete();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}

