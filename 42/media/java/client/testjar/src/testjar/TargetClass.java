package testjar;

public class TargetClass {
    public static int get_42() {
        return 42;
    }
    
    public static String getString() {
        return "hello";
    }
    
    public static CustomObject getCustomObject() {
        return new CustomObject(100, "original");
    }
}

