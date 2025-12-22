package testjar;

public class MatchNoArgsTarget {
    // Flag to track which patch was called (set by patches)
    public static String patchCalled = null;
    
    // Method with no parameters
    public static int getValue() {
        return 42;
    }
    
    // Method with one parameter (overloaded)
    public static int getValue(int x) {
        return x * 10;
    }
    
    // Method with two parameters (overloaded)
    public static int getValue(int x, int y) {
        return x * y;
    }
}

