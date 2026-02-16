package testjar;

public class OverloadedMethodsA extends OverloadedMethods {
    public static int calculate(int x) {
        return OverloadedMethods.calculate(x);
    }

    public static int calculate(int x, int y) {
        return OverloadedMethods.calculate(x, y);
    }
}

