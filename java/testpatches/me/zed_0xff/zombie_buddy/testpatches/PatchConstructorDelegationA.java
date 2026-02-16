package me.zed_0xff.zombie_buddy.testpatches;

import me.zed_0xff.zombie_buddy.Accessor;
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

        Accessor.trySet(self, "value", Integer.valueOf(value * 10));
        Accessor.trySet(self, "name", name + " patched");

        Accessor.trySet(self, "patchIntercepted", Boolean.TRUE);
        System.out.println("[ZB TEST] Setting patchIntercepted=true");
        Object patchIntercepted = Accessor.tryGet(self, "patchIntercepted", Boolean.FALSE);
        System.out.println("[ZB TEST] patchIntercepted is now: " + patchIntercepted);
    }
}

