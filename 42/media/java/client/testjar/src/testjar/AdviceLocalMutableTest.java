package testjar;

public class AdviceLocalMutableTest {
    // Flag to track the value passed between enter and exit
    public static int sharedValue = 0;
    public static boolean enterCalled = false;
    public static boolean exitCalled = false;
    
    // Method to be patched - will use @Local with mutable wrapper to share values
    public static int calculate(int x, int y) {
        return x + y;
    }
}

