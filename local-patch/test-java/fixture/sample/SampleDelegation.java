package fixture.sample;
import me.zed_0xff.zombie_buddy.Patch;
@Patch(className="fixture.target.Delegated", methodName="value", isAdvice=false)
public final class SampleDelegation { public static int value() { return 91; } }
