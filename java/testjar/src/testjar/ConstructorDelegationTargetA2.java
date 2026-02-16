package testjar;

/**
 * Test target class for testing MethodDelegation with constructor replacement.
 */
public class ConstructorDelegationTargetA2 {
    private int value;
    private String name;
    public boolean constructorCalled = false;
    public boolean patchIntercepted = false;
    public int defaultField = 42;
    
    // Constructor with parameters to test delegation
    public ConstructorDelegationTargetA2(int value, String name) {
        this.value = value;
        this.name = name;
        this.constructorCalled = true;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getName() {
        return name;
    }
}

