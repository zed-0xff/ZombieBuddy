package fixture.preload;

public final class PreMain {
    public static void premain(String[] args) {
        System.setProperty("fixture.preload.executed", "yes");
    }
}
