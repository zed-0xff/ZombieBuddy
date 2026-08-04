package me.zed_0xff.zombie_buddy.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.TYPE})
@Internal.MetaRoot
public @interface Shadow {
    String className();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Field {
        // @Internal.Flags(inferFromTargetName = true, probeField = true)
        String[] value() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Method {
        // @Internal.Flags(inferFromTargetName = true, probeMethod = true)
        String[] value() default {};  // empty = infer from parameter name; multiple = try in order
    }

    /*
     * cast shadow to target type
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Cast {
    }
}
