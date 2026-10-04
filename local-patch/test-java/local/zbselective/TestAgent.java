package local.zbselective;
import java.lang.instrument.Instrumentation;
public final class TestAgent {
    public static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation inst) { instrumentation = inst;
        if (Boolean.getBoolean("test.failHttp")) {
            try {
                Class<?> clients = Class.forName("me.zed_0xff.zombie_buddy.NetworkClients");
                var field = clients.getDeclaredField("factory"); field.setAccessible(true);
                field.set(null, (java.util.function.Function<Boolean,java.net.http.HttpClient>) follow -> {
                    throw new IllegalStateException("isolated test: network unavailable");
                });
            } catch (Exception error) { throw new IllegalStateException(error); }
        } }
}
