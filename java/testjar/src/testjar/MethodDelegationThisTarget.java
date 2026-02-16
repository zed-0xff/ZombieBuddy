package testjar;

/**
 * Test target class for testing MethodDelegation with @Patch.This as Object.
 */
public class MethodDelegationThisTarget {
    private int value = 10;
    
    // Instance method to test @Patch.This as Object with MethodDelegation
    public int multiply(int x) {
        return value * x;
    }
}

