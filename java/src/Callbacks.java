package me.zed_0xff.zombie_buddy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Callbacks {
    private Callbacks() {}

    enum CBType {
        ONCE,
        MANY
    }

    /** A single-fire Callback that can have multiple listeners. */
    public static final class Callback {
        private final String name;
        private final CBType type;
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        private boolean fired = false;

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

            Logger.info("Running callbacks for " + name);
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    public static final Callback onGameInitComplete = new Callback("onGameInitComplete", CBType.ONCE);
    public static final Callback onDisplayCreate    = new Callback("onDisplayCreate",    CBType.MANY);
}

