package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;

import java.lang.annotation.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.implementation.bind.annotation.*;

@Patch(className = "zombie.GameWindow", methodName = "init")
public class Patch_Loading_Screen {
  public static void intercept(@Origin Method method, @SuperCall Callable<?> original) throws Exception {
    original.call();
  }
}
