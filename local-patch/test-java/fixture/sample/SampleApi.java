package fixture.sample;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
@Exposer.LuaClass
public final class SampleApi {
    @LuaMethod(name="SampleGlobal", global=true)
    public static int sampleGlobal() { return 3; }
}
