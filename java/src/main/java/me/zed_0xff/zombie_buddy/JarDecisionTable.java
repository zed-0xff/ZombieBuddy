package me.zed_0xff.zombie_buddy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory allow/deny decisions for Java mod JARs, keyed by SHA-256 hash.
 * Each JAR binary is uniquely identified by its hash; the decision is "yes" or "no".
 */
public final class JarDecisionTable {

    private final Map<String, String> byHash = new HashMap<>();

    public String get(String sha256) {
        return sha256 == null ? null : byHash.get(sha256);
    }

    public void put(String sha256, String decision) {
        if (sha256 != null && decision != null) {
            byHash.put(sha256, decision);
        }
    }

    public boolean contains(String sha256) {
        return sha256 != null && byHash.containsKey(sha256);
    }

    public Set<String> hashes() {
        return byHash.keySet();
    }

    public boolean isEmpty() {
        return byHash.isEmpty();
    }

    public int size() {
        return byHash.size();
    }

    public JarDecisionTable copy() {
        JarDecisionTable c = new JarDecisionTable();
        c.byHash.putAll(this.byHash);
        return c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JarDecisionTable that)) return false;
        return byHash.equals(that.byHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byHash);
    }
}
