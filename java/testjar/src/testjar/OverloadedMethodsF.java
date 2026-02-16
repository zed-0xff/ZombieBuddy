package testjar;

public class OverloadedMethodsF extends OverloadedMethods {
    // Flag to track which patch was called (set by patches)
    public static String patchCalled = null;
    
    // Method with single parameter
    public static int calculate(int x) {
        return OverloadedMethods.calculate(x);
    }

    // Method with two parameters (overloaded)
    public static int calculate(int x, int y) {
        return OverloadedMethods.calculate(x, y);
    }
    
    // Method with three parameters (overloaded)
    public static int calculate(int x, int y, int z) {
        return x * y * z;
    }
}

