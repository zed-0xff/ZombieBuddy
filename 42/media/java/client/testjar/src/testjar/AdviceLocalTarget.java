package testjar;

public class AdviceLocalTarget {
    // Flags to track values passed between enter and exit
    public static int localIntValue = 0;
    public static Integer localIntegerValue = null;
    public static boolean primitiveInitialized = false;
    public static boolean referenceInitialized = false;
    public static boolean enterCalled = false;
    public static boolean exitCalled = false;
    public static boolean patchCalled = false;
    
    // Instance field to test @Patch.This with instance methods
    public boolean instancePatchCalled = false;
    private int value = 10;
    
    // Method to be patched - will use @Local to share values between enter and exit
    public static int calculate(int x, int y) {
        return x + y;
    }
    
    // Instance method to test @Patch.This as Object
    public int multiply(int x) {
        return value * x;
    }
    
    public int getValue() {
        return value;
    }
}

