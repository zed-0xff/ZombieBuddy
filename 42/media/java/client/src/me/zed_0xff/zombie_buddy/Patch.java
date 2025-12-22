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
  boolean warmUp() default false;  // mandatory for some internal classes like LuaManager$Exposer or the patch will not be applied
  boolean IKnowWhatIAmDoing() default false; // if true, the patch will be applied even if it is risky
  boolean strictMatch() default false; // if true, advice methods without arguments match only methods with no arguments
                                       // if false (default), advice methods without arguments match any method
  
  /** Alias for net.bytebuddy.asm.Advice.OnMethodEnter - mods should use Patch.OnEnter instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface OnEnter {
    Class<? extends Throwable>[] skipOn() default {};
  }
  
  /** Alias for net.bytebuddy.asm.Advice.OnMethodExit - mods should use Patch.OnExit instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface OnExit {
    Class<? extends Throwable>[] onThrowable() default {};
    Class<? extends Throwable>[] skipOn() default {};
  }
  
  /** Alias for net.bytebuddy.asm.Advice.Return - mods should use Patch.Return instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Return {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.This - mods should use Patch.This instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface This {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.Argument - mods should use Patch.Argument instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Argument {
    int value() default 0;
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.asm.Advice.AllArguments - mods should use Patch.AllArguments instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface AllArguments {
    boolean readOnly() default true;
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.RuntimeType - mods should use Patch.RuntimeType instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface RuntimeType {
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.SuperMethod - mods should use Patch.SuperMethod instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperMethod {
  }
  
  /** Alias for net.bytebuddy.implementation.bind.annotation.SuperCall - mods should use Patch.SuperCall instead */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface SuperCall {
  }
}
