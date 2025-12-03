package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Patch {
  String className();
  String methodName();
  boolean isAdvice() default true; // false => MethodDelegation
                                   // warning! advices can be chained, delegations can't, so only one delegation per method EVER
  boolean warmUp() default false; // If true, preloads the target class using AgentBuilder.warmUp() before transformation.
                                   // Use this when a class might not be loaded yet but needs to be transformed immediately.
                                   // This is necessary for classes that are loaded lazily or after the agent is installed.
}
