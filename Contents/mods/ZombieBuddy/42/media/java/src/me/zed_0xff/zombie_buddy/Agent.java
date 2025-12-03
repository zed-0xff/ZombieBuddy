package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ZB] installing Agent ..");
        Loader.g_instrumentation = inst;

        if (agentArgs != null && !agentArgs.isEmpty()) {
            System.out.println("[ZB] agentArgs: " + agentArgs);
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                String key = kv[0].toLowerCase();
                String value = (kv.length > 1) ? kv[1] : "";

                switch (key) {
                    case "verbosity":
                        try {
                            Loader.g_verbosity = Integer.parseInt(value);
                            System.err.println("[ZB] set verbosity to " + Loader.g_verbosity);
                        } catch (NumberFormatException e) {
                            System.err.println("[ZB] invalid verbosity value: " + value);
                        }
                        break;

                    case "exit_after_game_load":
                        Loader.g_exit_after_game_load = true;
                        System.err.println("[ZB] will exit after game load");
                        break;

                    default:
                        System.err.println("[ZB] unknown agent argument: " + key);
                        break;
                }
            }
        }

        Loader.ApplyPatchesFromPackage(ZombieBuddy.class.getPackage().getName() + ".patches", null, true);
        System.out.println("[ZB] Agent installed.");
    }
}
