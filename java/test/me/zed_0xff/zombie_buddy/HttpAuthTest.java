package me.zed_0xff.zombie_buddy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAuthTest {

    @Test
    void acceptsLongPrintableTokens() {
        assertTrue(HttpAuth.isValidToken("a".repeat(32)));
        assertTrue(HttpAuth.isValidToken(HttpAuth.generateToken()));
        assertTrue(HttpAuth.isValidToken("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"));
    }

    @Test
    void rejectsMissingShortOrWhitespaceTokens() {
        assertFalse(HttpAuth.isValidToken(null));
        assertFalse(HttpAuth.isValidToken(""));
        assertFalse(HttpAuth.isValidToken("short"));
        assertFalse(HttpAuth.isValidToken("a".repeat(31)));
        assertFalse(HttpAuth.isValidToken("a".repeat(30) + " b"));
        assertFalse(HttpAuth.isValidToken("a".repeat(31) + ","));
    }

    @Test
    void extractsHeaderTokenBeforeBearerToken() {
        assertEquals("header-token", HttpAuth.extractToken("header-token", "Bearer bearer-token"));
        assertEquals("bearer-token", HttpAuth.extractToken(null, "Bearer bearer-token"));
        assertEquals("bearer-token", HttpAuth.extractToken("  ", "bearer bearer-token"));
        assertNull(HttpAuth.extractToken(null, null));
        assertNull(HttpAuth.extractToken(null, "Basic dXNlcjpwYXNz"));
        assertNull(HttpAuth.extractToken("  ", "Bearer   "));
    }

    @Test
    void comparesTokensWithoutAcceptingMissingValues() {
        String token = "a".repeat(64);
        assertTrue(HttpAuth.isAuthorized(token, token));
        assertFalse(HttpAuth.isAuthorized(token, "b".repeat(64)));
        assertFalse(HttpAuth.isAuthorized(token, null));
        assertFalse(HttpAuth.isAuthorized(null, token));
        assertFalse(HttpAuth.isAuthorized("", token));
    }

    @Test
    void generatesDistinctHexTokens() {
        String first = HttpAuth.generateToken();
        String second = HttpAuth.generateToken();

        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
    }

    @Test
    void defaultsToLoopbackAndRejectsOtherHosts() {
        assertTrue(HttpAuth.isLoopbackHost(null));
        assertTrue(HttpAuth.isLoopbackHost(""));
        assertTrue(HttpAuth.isLoopbackHost("127.0.0.1"));
        assertTrue(HttpAuth.isLoopbackHost("localhost"));
        assertTrue(HttpAuth.isLoopbackHost("::1"));
        assertFalse(HttpAuth.isLoopbackHost("0.0.0.0"));
        assertFalse(HttpAuth.isLoopbackHost("*"));
        assertFalse(HttpAuth.isLoopbackHost("192.0.2.1"));
        assertFalse(HttpAuth.isLoopbackHost("example.com"));
    }
}
