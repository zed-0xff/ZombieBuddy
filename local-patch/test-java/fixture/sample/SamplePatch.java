package fixture.sample;
import me.zed_0xff.zombie_buddy.Patch;
@Patch(className="fixture.target.Target", methodName="value", strictMatch=true)
public final class SamplePatch {
    @Patch.OnExit public static void exit(@Patch.Return(readOnly=false) int value) { value = 73; }
}
