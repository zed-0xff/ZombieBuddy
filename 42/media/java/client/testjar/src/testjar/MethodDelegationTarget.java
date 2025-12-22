package testjar;

public class MethodDelegationTarget {
    // Flag to track if the method was called
    public static boolean wasCalled = false;
    
    // Method to be patched with MethodDelegation
    public static int multiply(int x, int y) {
        wasCalled = true;
        return x * y;
    }
    
    // Method with no parameters
    public static String getMessage() {
        return "original";
    }
}

