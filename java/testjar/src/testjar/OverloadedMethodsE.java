package testjar;

public class OverloadedMethodsE extends OverloadedMethods {
    // Flag to track which patch was called (set by patches)
    public static String patchCalled = null;
    
    public static int calculate(int x) {
        return OverloadedMethods.calculate(x);
    }

    public static int calculate(int x, int y) {
        return OverloadedMethods.calculate(x, y);
    }
}

