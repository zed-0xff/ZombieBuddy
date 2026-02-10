package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Method;

public final class Utils {

    private Utils() {
    }

    /**
     * Returns true if the object's class has a public method with the given name
     * and no parameters.
     */
    public static boolean hasMethod(Object obj, String methodName) {
        if (obj == null || methodName == null) {
            return false;
        }
        for (Method m : obj.getClass().getMethods()) {
            if (methodName.equals(m.getName())) {
                return true;
            }
        }
        return false;
    }
}
