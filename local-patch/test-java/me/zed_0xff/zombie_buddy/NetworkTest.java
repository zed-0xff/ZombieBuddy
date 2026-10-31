package me.zed_0xff.zombie_buddy;

import java.nio.file.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class NetworkTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger builds = new AtomicInteger();
        if (args[0].equals("lazy")) {
            NetworkClients.factory = follow -> { builds.incrementAndGet(); return HttpClient.newHttpClient(); };
            Class.forName(SteamWorkshop.class.getName());
            Class.forName(KnownAuthors.class.getName());
            Class.forName(ZBSVerifier.class.getName());
            if (builds.get() != 0) throw new AssertionError("Class initialization must not build HTTP clients");
            if (NetworkClients.get(true) != NetworkClients.get(true)) throw new AssertionError("redirect client shared");
            if (NetworkClients.get(false) != NetworkClients.get(false)) throw new AssertionError("direct client shared");
            if (builds.get() != 2) throw new AssertionError("build each network client exactly once");
        } else {
            Agent.arguments.put("config_dir", Files.createTempDirectory("zb-network-failure-").toString());
            NetworkClients.factory = follow -> { builds.incrementAndGet(); throw new ExceptionInInitializerError("simulated HTTP constructor failure"); };
            if (!KnownAuthors.loadAuthors().isEmpty()) throw new AssertionError("no author trust invented on failure");
            if (!KnownAuthors.loadAuthors().isEmpty()) throw new AssertionError("fallback remains empty");
            var id = new SteamWorkshop.WorkshopItemID(123);
            var details = SteamWorkshop.fetchItemDetails(Set.of(id)).get(id);
            if (details == null || details.ban().status() != null) throw new AssertionError("failed network means unknown ban, not approved");
            if (builds.get() != 2) throw new AssertionError("failed initialization must not be retried repeatedly");
        }
        System.out.println("PASS network " + args[0]);
    }
}
