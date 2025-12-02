package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import io.github.classgraph.*;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

public class Agent {
  // This method is called automatically when loaded as a javaagent
  public static void premain(String agentArgs, Instrumentation inst) {
    System.out.println("[ZB] installing Agent ..");

    AgentBuilder builder = new AgentBuilder.Default();
    List<Class<?>> patches = CollectPatches();

    for (Class<?> patch : patches) {
      Patch ann = patch.getAnnotation(Patch.class);
      if (ann == null) continue;

      System.out.println("[ZB] patching " + ann.className() + "." + ann.methodName() + " ..");

      builder = builder
        .type(ElementMatchers.named(ann.className()))
        .transform((b, td, cl, m, pd) ->
            b.method(ElementMatchers.named(ann.methodName()))
            .intercept(MethodDelegation.to(patch)) // or Advice.to
            );
    }

    builder.installOn(inst);
    System.out.println("[ZB] Agent installed.");
  }

  public static List<Class<?>> CollectPatches() {
    List<Class<?>> patches = new ArrayList<>();

    try (ScanResult scanResult = new ClassGraph()
            .enableAllInfo()      // Scan everything (annotations, methods, etc.)
            .acceptPackages(Agent.class.getPackage().getName()) // Limit scan to our package
            .scan()) {

        // Find all classes annotated with @Patch
        for (ClassInfo classInfo : scanResult.getClassesWithAnnotation(Patch.class.getName())) {
            Class<?> patchClass = classInfo.loadClass();
            System.out.println("[ZB] Found patch class: " + patchClass.getName());
            patches.add(patchClass);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }

    return patches;
  }
}
