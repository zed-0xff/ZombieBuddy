package testjar;

public class SkipOnTarget {
    public static int counter = 0;
    public static int testMethod(int x) {
        counter++;
        return x * 2;
    }
}
