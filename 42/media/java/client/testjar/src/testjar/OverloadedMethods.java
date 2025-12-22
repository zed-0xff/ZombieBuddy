package testjar;

public class OverloadedMethods {
    // Flag to track which patch was called (set by patches)
    public static String patchCalled = null;
    
    // Method with single parameter
    public static int calculate(int x) {
        return x * 10;
    }
    
    // Method with two parameters (overloaded)
    public static int calculate(int x, int y) {
        return x * y;
    }
}

