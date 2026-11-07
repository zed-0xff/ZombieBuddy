package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** This optional adapter keeps the author-choice PR independent of the Coop transport PR. */
class CoopSessionAuthorChoicesTest {
    @TempDir Path root;

    @Test void transfersOnlyProcessOverridesAndRetainsUntrustWithoutMutatingPersistentConfig() {
        String previous = Agent.arguments.get("config_dir");
        try {
            Agent.arguments.put("config_dir", root.resolve("client").toString());
            var stored = new SteamWorkshop.SteamID64(76561198000000010L);
            var temporary = new SteamWorkshop.SteamID64(76561198000000011L);
            Config config = new Config().withTrustedAuthor(stored);
            assertTrue(AuthorTrustChoices.coopSessionChoices(config).isEmpty());
            Map<String, Boolean> choices = Map.of(stored.toString(), false, temporary.toString(), true);
            AuthorTrustChoices.applyCoopSessionChoices(choices);
            Map<String, Boolean> snapshot = AuthorTrustChoices.coopSessionChoices(config);
            assertEquals(choices, snapshot);
            snapshot.clear();
            assertEquals(choices, AuthorTrustChoices.coopSessionChoices(config));
            assertFalse(AuthorTrustChoices.isTrusted(stored, config));
            assertTrue(AuthorTrustChoices.isTrusted(temporary, config));
            assertTrue(config.trustsAuthor(stored));
            assertFalse(config.trustsAuthor(temporary));
            Agent.arguments.put("config_dir", root.resolve("unrelated").toString());
            assertTrue(AuthorTrustChoices.coopSessionChoices(config).isEmpty());
            assertTrue(AuthorTrustChoices.isTrusted(stored, config));
            assertFalse(AuthorTrustChoices.isTrusted(temporary, config));
        } finally {
            if (previous == null) Agent.arguments.remove("config_dir"); else Agent.arguments.put("config_dir", previous);
        }
    }
}
