package me.zed_0xff.zombie_buddy.frontend;

import java.util.Locale;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Logger;

/**
 * Resolves {@link ModApprovalFrontend} from the {@code frontend} agent argument.
 *
 * <p>Values: {@code auto} (default), {@code swing} (javax.swing batch subprocess only),
 * {@code tinyfd} (LWJGL TinyFileDialogs only), {@code console} (stdin/stdout; for headless servers),
 * {@code imgui} (in-game PZ ImGui PoC approval).
 */
public final class ModApprovalFrontends {

    public static final String ARG_AUTO    = "auto";
    public static final String ARG_SWING   = "swing";
    public static final String ARG_TINYFD  = "tinyfd";
    public static final String ARG_CONSOLE = "console";
    public static final String ARG_IMGUI   = "imgui";

    private static final String TINYFD_CLASS         = "org.lwjgl.util.tinyfd.TinyFileDialogs";
    private static final String LWJGLX_DISPLAY_CLASS = "org.lwjglx.opengl.Display";
    private static final String GAME_SERVER_CLASS    = "zombie.network.GameServer";

    private ModApprovalFrontends() {}

    public static ModApprovalFrontend resolve(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || ARG_AUTO.equals(v)) {
            return resolveAuto();
        }
        if (ARG_SWING.equals(v)) {
            return new SwingModApprovalFrontend();
        }
        if (ARG_TINYFD.equals(v)) {
            if (Accessor.findClass(TINYFD_CLASS) == null) {
                Logger.warn("frontend=tinyfd but " + TINYFD_CLASS + " not found; using auto");
                return resolveAuto();
            }
            return new TinyfdModApprovalFrontend();
        }
        if (ARG_CONSOLE.equals(v)) {
            return new ConsoleModApprovalFrontend();
        }
        if (ARG_IMGUI.equals(v)) {
            if (!lwjglxDisplayWindowReady()) {
                Logger.warn("frontend=imgui but game window is not ready; using auto");
                return resolveAuto();
            }
            return new ImguiModApprovalFrontend();
        }
        Logger.warn("Unknown frontend '" + value + "'; using auto");
        return resolveAuto();
    }

    /**
     * PZ runs with {@code java.awt.headless=true}, so {@link java.awt.GraphicsEnvironment#isHeadless()}
     * is not a reliable signal. We use LWJGLX {@code Display.isCreated()} (game window) and
     * {@code GameServer.server} (dedicated / {@code -Dserver=true}) to pick a UI.
     *
     * <ul>
     *   <li>Display exists → {@link SwingModApprovalFrontend} (typical client / SP)</li>
     *   <li>No display but dedicated server process → {@link ConsoleModApprovalFrontend}</li>
     *   <li>Otherwise → {@link TinyfdModApprovalFrontend} (dialogs without Swing batch)</li>
     * </ul>
     */
    public static ModApprovalFrontend resolveAuto() {
        if (lwjglxDisplayIsCreated()) {
            return new ImguiModApprovalFrontend();
        }
        if (gameServerDedicatedFlag()) {
            return new ConsoleModApprovalFrontend();
        }
        return new TinyfdModApprovalFrontend();
    }

    /**
     * True when the LWJGLX OpenGL window has been created (normal game client).
     * Must not run during agent {@code premain}: loading {@code Display} runs GLFW static init and can throw
     * on the wrong thread (e.g. macOS). Callers should resolve the frontend lazily from game code.
     */
    private static boolean lwjglxDisplayIsCreated() {
        try {
            Class<?> displayClass = Accessor.findClass(LWJGLX_DISPLAY_CLASS);
            if (displayClass == null) {
                return false;
            }
            Object r = Accessor.callByName(displayClass, "isCreated");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean lwjglxDisplayWindowReady() {
        try {
            Class<?> displayClass = Accessor.findClass(LWJGLX_DISPLAY_CLASS);
            if (displayClass == null) {
                return false;
            }
            Object created = Accessor.callByName(displayClass, "isCreated");
            if (!Boolean.TRUE.equals(created)) {
                return false;
            }
            Object window = Accessor.callByName(displayClass, "getWindow");
            return window instanceof Long && ((Long) window).longValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mirrors {@code zombie.network.GameServer#server} (dedicated JAR / {@code -Dserver=true}). */
    private static boolean gameServerDedicatedFlag() {
        Class<?> gs = Accessor.findClass(GAME_SERVER_CLASS);
        if (gs == null) {
            return false;
        }
        Object v = Accessor.tryGet(gs, "server", Boolean.FALSE);
        return Boolean.TRUE.equals(v);
    }
}
