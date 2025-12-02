package me.zed_0xff.zombie_buddy;

import net.bytebuddy.implementation.bind.annotation.SuperCall;
import java.util.concurrent.Callable;

// Advice class modifies the method return value
@Patch(className = "zombie.core.Core", methodName = "getSVNRevisionString")
public class Core_Patch {
  // Advice style
  //
  // @Advice.OnMethodExit
  // static void exit(@Advice.Return(readOnly = false) String ret) {
  //   if (ret != null) {
  //     ret += " [ZB]";
  //   }
  // }

  // MethodDelegation style
  public static String getSVNRevisionString(@SuperCall Callable<String> zuper) throws Exception {
    return zuper.call() + " [ZB]";
  }
}
