package me.zed_0xff.zombie_buddy;

/** Strongly-typed Steam account id value object. */
public record SteamID64(long value) {
    public SteamID64(String s) {
        this(Long.parseLong(s));
    }
    
    @Override
    public String toString() {
        return Long.toString(value);
    }
}
