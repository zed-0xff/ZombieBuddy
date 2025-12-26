package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Test patch using MethodDelegation to replace a constructor with parameters.
 */
@Patch(
    className = "testjar.ConstructorDelegationTarget",
    methodName = "<init>",
    isAdvice = false
)
public class PatchConstructorDelegation {
    
    @Patch.RuntimeType
    public static void constructor(
            @Patch.This Object self,
            @Patch.Argument(0) int value,
            @Patch.Argument(1) String name) throws Throwable
    {
        System.out.println("[ZB TEST] PatchConstructorDelegation.constructor called with value=" + value + ", name=" + name);
        // Get the allocated instance (ByteBuddy allocates it, but it's uninitialized)
        testjar.ConstructorDelegationTarget instance = (testjar.ConstructorDelegationTarget) self;
        
        // Initialize fields manually without calling the original constructor
        java.lang.reflect.Field valueField = testjar.ConstructorDelegationTarget.class.getDeclaredField("value");
        valueField.setAccessible(true);
        valueField.setInt(instance, value);
        
        java.lang.reflect.Field nameField = testjar.ConstructorDelegationTarget.class.getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(instance, name);
        
        // Don't set constructorCalled to true - original constructor was NOT called
        // constructorCalled remains false
        
        // Mark that the patch intercepted it
        System.out.println("[ZB TEST] Setting patchIntercepted=true");
        instance.patchIntercepted = true;
        System.out.println("[ZB TEST] patchIntercepted is now: " + instance.patchIntercepted);
    }
}

