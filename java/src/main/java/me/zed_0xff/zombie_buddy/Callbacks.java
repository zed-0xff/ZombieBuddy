package me.zed_0xff.zombie_buddy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// not 'Events' to not mix stuff with PZ API
public final class Callbacks {
    public static final Callback onGameInitComplete = new Callback("onGameInitComplete", CBType.ONCE);
    public static final Callback onDisplayCreate    = new Callback("onDisplayCreate",    CBType.MANY);
    public static final Callback afterExposeAll     = new Callback("afterExposeAll",     CBType.MANY);
    public static final Callback onEndFrameUI       = new Callback("onEndFrameUI",       CBType.FREQUENT);
    public static final Callback beforeLuaInit      = new Callback("beforeLuaInit",      CBType.MANY);
    public static final Callback afterLuaInit       = new Callback("afterLuaInit",       CBType.MANY);

    private Callbacks() {}

    enum CBType {
        ONCE,
        MANY,
        FREQUENT // for callbacks that can be fired multiple times per frame (e.g. onRender)
    }

    /** A single-fire Callback that can have multiple listeners. */
    public static final class Callback {
        private final String name;
        private final CBType type;
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        private boolean fired = false;
        private int errCount = 0;

        private Callback(String name, CBType type) {
            this.name = name;
            this.type = type;
        }

        public void register(Runnable callback) {
            if (callback != null) {
                callbacks.add(callback);
            }
        }

        public void run() {
            if (fired && type == CBType.ONCE) return;
            fired = true;

            if (type != CBType.FREQUENT) {
                Logger.info("Running " + callbacks.size() + " callbacks for " + name);
            }

            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Throwable t) {
                    errCount++;
                    if (errCount < 50) {
                        Logger.printStackTrace(t);
                    } else if (errCount == 50) {
                        Logger.error("Too many errors in " + name + " callbacks; suppressing further error logs.");
                    }
                }
            }
        }
    }
}

