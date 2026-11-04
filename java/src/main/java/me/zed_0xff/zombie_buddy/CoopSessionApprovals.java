package me.zed_0xff.zombie_buddy;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static me.zed_0xff.zombie_buddy.ModFlags.*;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

/** One-launch client decisions for a read-only local Coop child; never an approval store. */
public final class CoopSessionApprovals {
    public static final String FILE_PROPERTY = "zb.coop.session.file";
    public static final String NONCE_PROPERTY = "zb.coop.session.nonce";
    static final int MAGIC = 0x5a424353;
    static final int VERSION = 1;
    static final int MAX_ENTRIES = 8192;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<Path> pending = ConcurrentHashMap.newKeySet();
    private static State state;

    private record State(boolean requested, boolean usable, String configScope, Map<String, Boolean> jars,
                         Map<String, Boolean> authors) {}

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path file : pending) cleanup(file);
        }, "ZombieBuddy-Coop-session-cleanup"));
    }

    private CoopSessionApprovals() {}

    /** Called after the ordinary command validation, immediately before ProcessBuilder. */
    public static List<String> launch(List<String> prepared) throws IOException {
        int main = prepared.indexOf("zombie.network.GameServer");
        if (main < 0 || !prepared.subList(main + 1, prepared.size()).contains("-coop")) return prepared;
        if (CoopChild.isReadOnly()) throw new IOException("A Coop child cannot publish client authority");
        Map<String, Boolean> jars = jarSnapshot();
        Map<String, Boolean> authors = authorSnapshot(clientConfig());
        Path file = publish(jars, authors, Agent.configDir());
        String nonce = file.getFileName().toString().substring(8, 40);
        List<String> result = new ArrayList<>(prepared);
        // Last JVM property wins; keep all handoff data outside the native option buffer.
        result.add(main++, "-D" + FILE_PROPERTY + "=" + file);
        result.add(main, "-D" + NONCE_PROPERTY + "=" + nonce);
        return result;
    }

    static Path publish(Map<String, Boolean> jars, Map<String, Boolean> authors, Path config) throws IOException {
        Map<String, Boolean> checkedJars = checked(jars, true);
        Map<String, Boolean> checkedAuthors = checked(authors, false);
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String nonce = HexFormat.of().formatHex(random);
        Path directory = Files.createTempDirectory("zombie-buddy-coop-");
        Path file = directory.resolve("session-" + nonce + ".bin");
        try {
            ownerOnly(directory, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(nonce);
                out.writeUTF(scope(config));
                writeMap(out, checkedJars);
                writeMap(out, checkedAuthors);
            }
            if (bytes.size() > MAX_BYTES) throw new IOException("Coop snapshot exceeds size limit");
            Files.createFile(file);
            ownerOnly(file, false);
            Files.write(file, bytes.toByteArray(), StandardOpenOption.WRITE);
            pending.add(file);
            // Failed/cancelled ProcessBuilder starts must not leave session authority indefinitely.
            CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(() -> {
                if (pending.contains(file)) cleanup(file);
            });
            return file;
        } catch (IOException | RuntimeException e) {
            cleanup(file);
            throw e;
        }
    }

    private static void ownerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("No owner-only file permission support");
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }

    private static String scope(Path config) throws IOException {
        String value = config.toAbsolutePath().normalize().toString();
        if (value.length() > 16384) throw new IOException("Coop configuration path too long");
        return value;
    }

    private static Map<String, Boolean> checked(Map<String, Boolean> source, boolean hashes) throws IOException {
        if (source == null || source.size() > MAX_ENTRIES) throw new IOException("Invalid Coop decision count");
        Map<String, Boolean> result = new TreeMap<>();
        for (var entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.matches(hashes ? "[0-9a-f]{64}" : "[0-9]{17}") || entry.getValue() == null)
                throw new IOException("Invalid Coop decision entry");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void writeMap(DataOutputStream out, Map<String, Boolean> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeByte(entry.getValue() ? 1 : 0);
        }
    }

    private static Map<String, Boolean> readMap(DataInputStream in, boolean hashes) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid Coop decision count");
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            int value = in.readUnsignedByte();
            if (value > 1 || result.putIfAbsent(key, value == 1) != null)
                throw new IOException("Invalid or duplicate Coop decision");
        }
        return checked(result, hashes);
    }

    private static State read(Path config) {
        String filename = System.getProperty(FILE_PROPERTY);
        String nonce = System.getProperty(NONCE_PROPERTY);
        if (filename == null && nonce == null) return new State(false, true, null, Map.of(), new HashMap<>());
        Path file = null;
        boolean mayCleanup = false;
        try {
            if (filename == null || nonce == null || !nonce.matches("[0-9a-f]{32}"))
                throw new IOException("Incomplete Coop handoff");
            file = Path.of(filename).toAbsolutePath().normalize();
            if (!file.getFileName().toString().equals("session-" + nonce + ".bin")
                    || file.getParent() == null || !file.getParent().getFileName().toString().startsWith("zombie-buddy-coop-")
                    || Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid Coop snapshot path");
            Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if (!file.getParent().toRealPath().getParent().equals(temporaryRoot))
                throw new IOException("Coop snapshot is outside the temporary directory");
            mayCleanup = true;
            if (Files.size(file) > MAX_BYTES) throw new IOException("Coop snapshot exceeds size limit");
            // NOFOLLOW closes the file-link race between validation and open.
            try (InputStream stream = Channels.newInputStream(Files.newByteChannel(file,
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
                 DataInputStream in = new DataInputStream(new ByteArrayInputStream(stream.readNBytes(MAX_BYTES + 1)))) {
                if (in.available() > MAX_BYTES || in.readInt() != MAGIC || in.readInt() != VERSION
                        || !nonce.equals(in.readUTF()) || !scope(config).equals(in.readUTF()))
                    throw new IOException("Invalid Coop snapshot header");
                Map<String, Boolean> jars = readMap(in, true);
                Map<String, Boolean> authors = readMap(in, false);
                if (in.read() != -1) throw new IOException("Trailing Coop snapshot data");
                authorImport(authors);
                return new State(true, true, scope(config), jars, new HashMap<>(authors));
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn("Local Coop session handoff is invalid; refusing Java mods (" + e.getClass().getSimpleName() + ")");
            return new State(true, false, null, Map.of(), new HashMap<>());
        } finally {
            if (mayCleanup) cleanup(file);
        }
    }

    private static synchronized State imported() {
        if (!CoopChild.isReadOnly()) return new State(false, true, null, Map.of(), Map.of());
        if (state == null) state = read(Agent.configDir());
        if (state.requested() && state.usable()) {
            try {
                if (!Objects.equals(state.configScope(), scope(Agent.configDir())))
                    throw new IOException("Coop configuration scope changed");
            } catch (IOException e) {
                Logger.warn("Local Coop session scope changed; refusing Java mods.");
                state = new State(true, false, null, Map.of(), new HashMap<>());
            }
        }
        return state;
    }

    public static boolean isUsable() { return imported().usable(); }

    public static void apply(JarDecisionTable decisions) {
        State current = imported();
        if (current.usable()) current.jars().forEach(decisions::put);
    }

    public static Boolean authorChoice(SteamID64 author) {
        State current = imported();
        return author == null || !current.usable() ? null : current.authors().get(Long.toString(author.value()));
    }

    public static void revokeAuthor(SteamID64 author) {
        State current = imported();
        if (current.requested() && author != null) {
            current.authors().put(Long.toString(author.value()), false);
            try { authorImport(Map.of(Long.toString(author.value()), false)); }
            catch (IOException e) { state = new State(true, false, null, Map.of(), new HashMap<>()); }
        }
    }

    /** Additional transient veto, after normal independent signature verification. */
    public static ZBSVerifier.CheckResult check(String hash, ZBSVerifier.CheckResult result) {
        State current = imported();
        if (!current.requested()) return result;
        if (result.flags().has(MF_BANNED)) revokeAuthor(result.uploaderID());
        if (current.usable() && result != ZBSVerifier.CheckResult.DISABLED && !result.flags().has(MF_VALID)) return result;
        boolean block = !current.usable();
        if (!block) {
            Boolean direct = current.jars().get(hash);
            boolean signed = result.flags().hasAll(MF_VALID, MF_SIGNED) && result.sid() != null;
            Boolean author = signed ? authorChoice(result.sid()) : null;
            boolean trusted = false;
            try { trusted = signed && (author != null ? author : clientConfig().trustsAuthor(result.sid())); }
            catch (IOException e) { block = true; }
            if (Boolean.FALSE.equals(direct) && !trusted) block = true;
            if (Boolean.FALSE.equals(author) && !Boolean.TRUE.equals(direct) && !"allow-all".equals(Loader.getPolicy())) {
                boolean savedAllow = false;
                for (var saved : ModApprovalsStore.load().mods) {
                    if (Objects.equals(hash, saved.jarHash)) savedAllow = saved.decision;
                }
                if (!savedAllow) block = true;
            }
        }
        if (!block) return result;
        String reason = "blocked by local Coop session decision";
        return new ZBSVerifier.CheckResult(result.flags().without(MF_VALID), result.sid(), result.uploaderID(),
                reason, reason, result.verification());
    }

    private static Object loaderExport(String name) throws IOException {
        try {
            // Narrow method adapters also let the stable binary port compile against official 2.3.4.
            Method method = Loader.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot snapshot client session decisions", e);
        }
    }

    private static Config clientConfig() throws IOException {
        return (Config) loaderExport("coopSessionConfig");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> jarSnapshot() throws IOException {
        return checked((Map<String, Boolean>) loaderExport("coopSessionJarDecisions"), true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> authorSnapshot(Config config) throws IOException {
        try {
            Class<?> type = Class.forName("me.zed_0xff.zombie_buddy.AuthorTrustChoices", false, CoopSessionApprovals.class.getClassLoader());
            Method method = type.getDeclaredMethod("coopSessionChoices", Config.class);
            method.setAccessible(true);
            return checked((Map<String, Boolean>) method.invoke(null, config), false);
        } catch (ClassNotFoundException absentIndependentPR) {
            return Map.of();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("Cannot snapshot process author decisions", e);
        }
    }

    private static void authorImport(Map<String, Boolean> authors) throws IOException {
        if (authors.isEmpty()) return;
        try {
            Class<?> type = Class.forName("me.zed_0xff.zombie_buddy.AuthorTrustChoices", false, CoopSessionApprovals.class.getClassLoader());
            Method method = type.getDeclaredMethod("applyCoopSessionChoices", Map.class);
            method.setAccessible(true);
            method.invoke(null, Collections.unmodifiableMap(authors));
        } catch (ClassNotFoundException absentIndependentPR) {
            // The independent Coop PR uses authorChoice directly; the approval PR supplies this adapter.
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot import process author decisions", e);
        }
    }

    private static void cleanup(Path file) {
        if (file == null || file.getParent() == null || Files.isSymbolicLink(file.getParent())
                || Files.isSymbolicLink(file)) return;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        try { Files.deleteIfExists(file.getParent()); } catch (IOException ignored) {}
        pending.remove(file);
    }
}
