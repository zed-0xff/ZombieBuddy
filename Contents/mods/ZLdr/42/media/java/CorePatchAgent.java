import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.annotation.*;

public class CorePatchAgent {
  // This method is called automatically when loaded as a javaagent
  public static void premain(String agentArgs, Instrumentation inst) {
    System.out.println("[d] CorePatchAgent is initializing...");

    new AgentBuilder.Default()
      .type(ElementMatchers.named("zombie.core.Core"))
      .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
          builder.method(ElementMatchers.named("getSVNRevisionString"))
          .intercept(Advice.to(AppendZBB.class))
          ).installOn(inst);

    System.out.println("[d] CorePatchAgent is installed.");
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface Patch {
    String className();
    String methodName();
  }

  // Advice class modifies the method return value
  @Patch(className = "zombie.core.Core", methodName = "getSVNRevisionString")
  public static class AppendZBB {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) String ret) {
      if (ret != null) {
        ret += " [ZBB]";
      }
    }
  }
}
