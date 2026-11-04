package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual independent agent JVMs; reuses the existing unsigned fixture and offline-loader harness. */
public final class CoopSessionApprovalsProbe {
    private static int assertions;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
    private static Object fixture(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = CoopReadOnlyProbe.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static boolean authorTrusted(SteamWorkshop.SteamID64 author) throws Exception {
        try {
            Class<?> choices = Class.forName("me.zed_0xff.zombie_buddy.AuthorTrustChoices");
            Method method = choices.getDeclaredMethod("isTrusted", SteamWorkshop.SteamID64.class, Config.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, author, Loader.coopSessionConfig());
        } catch (ClassNotFoundException independentCoopPR) {
            Method method = Loader.class.getDeclaredMethod("isAuthorTrusted", SteamWorkshop.SteamID64.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, author);
        }
    }
    private static void load(Path root, String id, boolean allowed) throws Exception {
        fixture("register", new Class<?>[]{Path.class, String.class}, root, id);
        fixture("load", new Class<?>[]{String.class}, id);
        check((boolean) fixture("active", new Class<?>[]{String.class}, id) == allowed, "Effective child decision for " + id);
    }
    private static void child(Path root, String mode) throws Exception {
        fixture("offline", new Class<?>[0]);
        zombie.core.random.RandStandard.INSTANCE.init();
        field(Loader.class, "g_hasDoLoadingText").set(null, false);
        if (mode.equals("missing")) {
            check(!CoopSessionApprovals.isUsable(), "Consumed snapshot cannot be replayed");
            load(root, "CoopAllowed", false);
        } else if (mode.equals("dedicated")) {
            check(!CoopChild.isReadOnly(), "Dedicated role unaffected");
            load(root, "CoopAllowed", true);
            load(root, "CoopDenied", false);
        } else {
            check(CoopSessionApprovals.isUsable(), "Valid child snapshot");
            load(root, "CoopAllowed", false);
            load(root, "CoopDenied", true);
            load(root, "CoopUnknown", true);
            Loader.initConfig();
            load(root, "CoopAllowed", false);
            load(root, "CoopDenied", true);
            var authorA = new SteamWorkshop.SteamID64(76561198000000010L);
            var authorB = new SteamWorkshop.SteamID64(76561198000000011L);
            if (mode.equals("authors")) {
                check(Boolean.FALSE.equals(CoopSessionApprovals.authorChoice(authorA)), "Temporary untrust preserved");
                check(Boolean.TRUE.equals(CoopSessionApprovals.authorChoice(authorB)), "Temporary trust preserved");
                check(!authorTrusted(authorA), "Imported untrust overrides stored trust");
                check(authorTrusted(authorB), "Imported trust applies without stored grant");
                CoopSessionApprovals.revokeAuthor(authorB);
                check(!authorTrusted(authorB), "Ban can revoke imported trust in memory");
            }
            Agent.arguments.put("config_dir", root.resolve("different-config").toString());
            check(!CoopSessionApprovals.isUsable(), "Authority cannot cross configuration scope");
        }
        System.out.println("PASS " + assertions + " actual Coop session assertions: " + mode);
    }
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toAbsolutePath();
        if (arguments.length > 1) { child(root, arguments[1]); return; }
        Path agent = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String id : List.of("CoopAllowed", "CoopDenied", "CoopUnknown"))
            fixture("fixture", new Class<?>[]{Path.class, String.class}, root, id);
        fixture("writeClientState", new Class<?>[]{Path.class, boolean.class}, root, false);
        Agent.arguments.put("config_dir", root.resolve("config").toString());
        Loader.initConfig();
        JarDecisionTable session = (JarDecisionTable) field(Loader.class, "g_sessionJarDecisions").get(null);
        session.put(Utils.sha256Hex(root.resolve("CoopAllowed/fixture.jar")), false);
        session.put(Utils.sha256Hex(root.resolve("CoopDenied/fixture.jar")), true);
        session.put(Utils.sha256Hex(root.resolve("CoopUnknown/fixture.jar")), true);
        List<String> prepared = new ArrayList<>(List.of("java", "-Dzb.coop.child=true", "zombie.network.GameServer", "-coop"));
        List<String> firstLaunch = CoopSessionApprovals.launch(prepared);
        List<String> secondLaunch = CoopSessionApprovals.launch(prepared);
        check(!firstLaunch.equals(secondLaunch), "Every host launch owns a distinct single-use handoff");
        Map<Path, byte[]> original = new LinkedHashMap<>();
        for (String name : List.of("config.json", "mod_approvals.json", "java_mod_approvals.txt")) {
            Path file = root.resolve("config").resolve(name); original.put(file, Files.readAllBytes(file));
        }
        for (String mode : List.of("normal", "missing", "authors", "dedicated")) {
            List<String> handoff = firstLaunch;
            if (mode.equals("authors")) {
                Path file = CoopSessionApprovals.publish(Loader.coopSessionJarDecisions(),
                        Map.of("76561198000000010", false, "76561198000000011", true), Agent.configDir());
                String nonce = file.getFileName().toString().substring(8, 40);
                handoff = List.of("-D" + CoopSessionApprovals.FILE_PROPERTY + "=" + file,
                        "-D" + CoopSessionApprovals.NONCE_PROPERTY + "=" + nonce);
            }
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xverify:all", "-Djava.awt.headless=true", "-Dserver=true"));
            if (!mode.equals("dedicated")) command.add("-Dzb.coop.child=true");
            for (String option : handoff) if (option.startsWith("-Dzb.coop.session.")) command.add(option);
            command.addAll(List.of("-javaagent:" + agent + "=policy=prompt,frontend=console,config_dir=" + root.resolve("config"),
                    "-cp", agent + File.pathSeparator + System.getProperty("java.class.path"),
                    CoopSessionApprovalsProbe.class.getName(), root.toString(), mode));
            Path log = root.resolve("session-" + mode + ".log");
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                check(child.waitFor(25, TimeUnit.SECONDS), "Child completed");
                check(child.exitValue() == 0, Files.readString(log));
                Files.readAllLines(log).stream().filter(line -> line.startsWith("PASS ")).forEach(System.out::println);
                if (!mode.equals("dedicated")) for (var entry : original.entrySet())
                    check(Arrays.equals(entry.getValue(), Files.readAllBytes(entry.getKey())), "Persistent client bytes unchanged");
            } finally { if (child.isAlive()) child.destroyForcibly(); }
        }
        System.out.println("PASS " + assertions + " Coop session parent assertions; no game/server started");
    }
}
