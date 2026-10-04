package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

/** Share lazy clients; initialization failure reaches the existing network fallback, not JVM clinit. */
final class NetworkClients {
    private static HttpClient direct, redirected;
    private static IOException directFailure, redirectedFailure;
    static Function<Boolean, HttpClient> factory = follow -> {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (follow) builder.followRedirects(HttpClient.Redirect.NORMAL);
        return builder.build();
    };

    static synchronized HttpClient get(boolean follow) throws IOException {
        HttpClient client = follow ? redirected : direct;
        if (client != null) return client;
        IOException previous = follow ? redirectedFailure : directFailure;
        if (previous != null) throw previous;
        try {
            client = factory.apply(follow);
            if (follow) redirected = client; else direct = client;
            return client;
        } catch (RuntimeException | LinkageError error) {
            IOException failure = new IOException("HTTP client unavailable; remote metadata cannot be fetched", error);
            if (follow) redirectedFailure = failure; else directFailure = failure;
            throw failure;
        }
    }
}
