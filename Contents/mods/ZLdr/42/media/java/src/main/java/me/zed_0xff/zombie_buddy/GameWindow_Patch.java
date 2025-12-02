package me.zed_0xff.zombie_buddy;

import java.lang.annotation.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.implementation.bind.annotation.*;

@Patch(className = "zombie.GameWindow", methodName = "init")
public class GameWindow_Patch {
  //@RuntimeType
  public static void intercept(@Origin Method method, @SuperCall Callable<?> original) throws Exception {
    System.out.println("[d] before " + method);
    original.call();
    System.out.println("[d] after " + method);
  }
}
