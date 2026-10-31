package me.zed_0xff.zombie_buddy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Authentication and bind-safety helpers for the experimental HTTP API.
 *
 * <p>The API executes arbitrary Lua, so it is never exposed without a bearer token.
 * The token is generated per process unless {@code lua_server_token} is supplied.
 * Tokens are compared in constant time and must not be placed in URLs.
 */
public final class HttpAuth {
    /** Minimum accepted token length. Generated tokens are 64 hex characters. */
    public static final int MIN_TOKEN_LENGTH = 32;

    /** Upper bound to keep header/argument handling sane. */
    private static final int MAX_TOKEN_LENGTH = 512;

    private static final SecureRandom RANDOM = new SecureRandom();

    private HttpAuth() {}

    /** Returns true when the token is long enough and contains only printable, non-whitespace ASCII. */
    public static boolean isValidToken(String token) {
        if (token == null) {
            return false;
        }
        String value = token.trim();
        if (value.length() < MIN_TOKEN_LENGTH || value.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Comma is rejected because agent arguments are comma-separated.
            if (c < 0x21 || c > 0x7e || c == ',') {
                return false;
            }
        }
        return true;
    }

    /** Generates a 256-bit token as 64 lowercase hex characters. */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Extracts a token from the preferred {@code X-ZombieBuddy-Token} header, falling
     * back to {@code Authorization: Bearer <token>}. Returns null when absent/malformed.
     */
    public static String extractToken(String headerToken, String authorization) {
        if (headerToken != null) {
            String value = headerToken.trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (authorization != null) {
            String value = authorization.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String bearer = value.substring(7).trim();
                if (!bearer.isEmpty()) {
                    return bearer;
                }
            }
        }
        return null;
    }

    /** Constant-time token check. A missing/blank configured token always fails. */
    public static boolean isAuthorized(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) {
            return false;
        }
        String expectedValue = expected.trim();
        String suppliedValue = supplied.trim();
        if (expectedValue.isEmpty() || suppliedValue.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            expectedValue.getBytes(StandardCharsets.UTF_8),
            suppliedValue.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns true for the loopback bind addresses the HTTP API accepts by default.
     * Anything else requires the explicit {@code lua_server_allow_remote=true} opt-in.
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String value = host.trim();
        return "127.0.0.1".equals(value)
            || "localhost".equalsIgnoreCase(value)
            || "::1".equals(value)
            || "0:0:0:0:0:0:0:1".equals(value);
    }
}
