package me.zed_0xff.zombie_buddy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class AuthorsVerify {
    public static void main(String[] args) throws Exception {
        String body = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        if (!KnownAuthors.verifySignature(body)) throw new AssertionError("Upstream authors signature invalid");
        var authors = KnownAuthors.parseAuthorsJSON(body);
        if (authors.size() != 10) throw new AssertionError("Unexpected upstream snapshot author count");
        if (!authors.containsKey(new SteamWorkshop.SteamID64(76561198258764906L)))
            throw new AssertionError("Expected upstream LaoYu entry absent");
        System.out.println("PASS official Ed25519 authors signature; count=" + authors.size());
    }
}
