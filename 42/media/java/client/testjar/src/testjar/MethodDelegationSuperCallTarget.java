package testjar;

public class MethodDelegationSuperCallTarget {
    // Flag to track if the method was called
    public static boolean wasCalled = false;
    
    // Method to be patched with MethodDelegation using @SuperCall
    public static int multiply(int x, int y) {
        wasCalled = true;
        return x * y;
    }
}

