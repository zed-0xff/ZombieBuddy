package fixture.sample;
import me.zed_0xff.zombie_buddy.Patch;
@Patch(className="/fixture\\.target\\.RegexTarget/", methodName="value", strictMatch=true)
public final class RegexPatch {
    @Patch.OnExit public static void exit(@Patch.Return(readOnly=false) int value) { value = 74; }
}
