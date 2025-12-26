package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Test patch using MethodDelegation to replace a constructor with parameters.
 */
@Patch(
    className = "testjar.ConstructorDelegationTargetA*",
    methodName = "<init>",
    isAdvice = false
)
public class PatchConstructorDelegationA {
    
    @Patch.RuntimeType
    public static void constructor(
            @Patch.This Object self,
            @Patch.Argument(0) int value,
            @Patch.Argument(1) String name) throws Throwable
    {
        System.out.println("[ZB TEST] PatchConstructorDelegation.constructor called with value=" + value + ", name=" + name);
        // Get the allocated instance (ByteBuddy allocates it, but it's uninitialized)
        // Use the actual class of self to support wildcard patterns matching multiple classes
        Class<?> instanceClass = self.getClass();
        
        // Initialize fields manually without calling the original constructor
        java.lang.reflect.Field valueField = instanceClass.getDeclaredField("value");
        valueField.setAccessible(true);
        valueField.setInt(self, value * 10);
        
        java.lang.reflect.Field nameField = instanceClass.getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(self, name + " patched");
        
        // Mark that the patch intercepted it
        java.lang.reflect.Field patchInterceptedField = instanceClass.getDeclaredField("patchIntercepted");
        patchInterceptedField.setAccessible(true);
        patchInterceptedField.setBoolean(self, true);
        System.out.println("[ZB TEST] Setting patchIntercepted=true");
        System.out.println("[ZB TEST] patchIntercepted is now: " + patchInterceptedField.getBoolean(self));
    }
}

