package testjar;

import java.util.Map;

public class FieldValueTarget {
    public String name = "initial";
    public int counter = 0;

    public static String capturedName = null;
    public static Object capturedCounter = null;
    public static Map<String, String> capturedNameMap = null;

    public void doSomething() {}
    public void doSomethingExplicit() {}
    public void captureNameMap() {}
    public void increment() {}
    public void incrementExplicit() {}
    public void readCounterBoxed() {}
    public void readNameAsObject() {}
}
