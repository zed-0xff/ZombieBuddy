package testjar;

public class CustomObject {
    private final int intValue;
    private final String stringValue;
    
    public CustomObject(int intValue, String stringValue) {
        this.intValue = intValue;
        this.stringValue = stringValue;
    }
    
    @Override
    public String toString() {
        return "CustomObject{intValue=" + intValue + ", stringValue='" + stringValue + "'}";
    }
}

