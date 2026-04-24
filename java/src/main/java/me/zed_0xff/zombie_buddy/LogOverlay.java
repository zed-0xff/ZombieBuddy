package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.HashSet;

import org.lwjgl.glfw.GLFW;

import zombie.core.Core;
import zombie.GameTime;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * Draws a scrolling game log overlay in the bottom-left corner of the screen.
 * New lines are added at the bottom and push older lines upward.
 * Each line fades independently based on its timestamp.
 * Toggle with Ctrl+` (backtick/grave accent).
 */
@Exposer.LuaClass(name = "ZombieBuddy.LogOverlay")
public class LogOverlay {
    private static final int MARGIN_LEFT       = 10;
    private static final int MARGIN_BOTTOM     = 10;
    private static final float ALPHA_MAX       = 0.4f;
    private static final long FADE_START_MS    = 4000; // Start fading after (ms)
    private static final long FADE_DURATION_MS = 700; // Fade out over (ms)
    private static final long LINE_LIFETIME_MS = FADE_START_MS + FADE_DURATION_MS;
    
    // GLFW key codes for grave/backtick (`) and section sign (§)
    private static final int KEY_GRAVE   = GLFW.GLFW_KEY_GRAVE_ACCENT;
    private static final int KEY_WORLD_1 = GLFW.GLFW_KEY_WORLD_1;
    
    private record Line(String text, long timestamp) {}
    
    private static final ConcurrentLinkedDeque<Line> lines = new ConcurrentLinkedDeque<>();
    private static boolean enabled = true;
    private static int maxLines = 100;
    private static boolean lastOsdKeyState = false;
    private static Field f_renderThisFrame = null;
    private static long pausedAt = 0;  // When game was paused (0 = not paused)
    private static boolean wasPaused = false;
    private static final HashSet<String> filters = new HashSet<>();

    public static void enable() {
        enabled = true;
    }
    
    public static void disable() {
        enabled = false;
    }
    
    public static boolean isEnabled() {
        return enabled;
    }
    
    public static void toggle() {
        enabled = !enabled;
    }

    public static void addFilter(String filter) {
        filters.add(filter);
    }

    public static void removeFilter(String filter) {
        filters.remove(filter);
    }

    public static void clearFilters() {
        filters.clear();
    }
    
    public static void checkOsdToggle() {
        try {
            if (!org.lwjglx.opengl.Display.isCreated()) return;
            
            long window = org.lwjglx.opengl.Display.getWindow();
            boolean ctrlPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            
            boolean osdKeyPressed = GLFW.glfwGetKey(window, KEY_GRAVE) == GLFW.GLFW_PRESS ||
                                    GLFW.glfwGetKey(window, KEY_WORLD_1) == GLFW.GLFW_PRESS;
            boolean osdKeyDown = ctrlPressed && osdKeyPressed;
            if (osdKeyDown && !lastOsdKeyState) {
                if (!enabled || !hasVisibleLines()) {
                    // Disabled or no visible lines -> enable and refresh timestamps
                    enabled = true;
                    refreshTimestamps();
                } else {
                    // Visible -> disable
                    enabled = false;
                }
            }
            lastOsdKeyState = osdKeyDown;
        } catch (Exception e) {
            // Ignore - display might not be ready
        }
    }
    
    private static boolean hasVisibleLines() {
        long now = System.currentTimeMillis();
        for (Line line : lines) {
            if (now - line.timestamp < LINE_LIFETIME_MS) return true;
        }
        return false;
    }
    
    private static void refreshTimestamps() {
        long now = System.currentTimeMillis();
        var oldLines = new java.util.ArrayList<>(lines);
        lines.clear();
        for (Line line : oldLines) {
            lines.addLast(new Line(line.text, now));
        }
    }
    
    public static void addLine(String text) {
        if (text == null || text.isEmpty()) return;

        for (String filter : filters) {
            if (text.contains(filter)) {
                return;
            }
        }
        
        lines.addLast(new Line(text, System.currentTimeMillis()));
        while (lines.size() > maxLines) {
            lines.removeFirst();
        }
    }
    
    public static void clear() {
        lines.clear();
    }
    
    public static void draw() {
        if (!enabled || lines.isEmpty()) return;
        
        // Prevent double-draw in same frame (use both frame index and time)
        long now = System.currentTimeMillis();
        // int frame = SpriteRenderer.instance != null ? SpriteRenderer.instance.getMainStateIndex() : 0;
        // if (frame == lastDrawFrame || now == lastDrawTime) return;
        // lastDrawFrame = frame;
        // lastDrawTime = now;
        
        var textMgr = TextManager.instance;
        if (textMgr == null) return;

        if (f_renderThisFrame == null) {
            f_renderThisFrame = Accessor.findField(Core.class, "uiRenderThisFrame", "UIRenderThisFrame");
        }
        if (f_renderThisFrame != null && Accessor.tryGet(Core.getInstance(), f_renderThisFrame, true) == false) {
            return;
        }
        
        var font = textMgr.font.isEmpty() ? UIFont.Small : UIFont.CodeSmall;
        var lineHeight = (int) textMgr.MeasureStringY(font, "Ay") + 2;
        var scrH = Core.getInstance().getScreenHeight();
        int y = scrH - MARGIN_BOTTOM;
        
        // Track pause state to freeze fade timing
        boolean paused = GameTime.isGamePaused();
        if (paused && !wasPaused) {
            pausedAt = now;  // Just became paused - freeze time
        } else if (!paused && wasPaused) {
            // Unpaused - adjust timestamps to account for pause duration
            long pauseDuration = now - pausedAt;
            var oldLines = new java.util.ArrayList<>(lines);
            lines.clear();
            for (Line line : oldLines) {
                lines.addLast(new Line(line.text, line.timestamp + pauseDuration));
            }
            pausedAt = 0;
        }
        wasPaused = paused;
        
        // Use frozen time when paused
        long effectiveNow = paused ? pausedAt : now;
        
        // Draw lines from bottom to top (newest at bottom)
        var iter = lines.descendingIterator();
        while (iter.hasNext()) {
            Line line = iter.next();
            
            // Calculate per-line alpha based on age
            long age = effectiveNow - line.timestamp;
            if (age >= LINE_LIFETIME_MS) continue; // Expired, skip
            
            float alpha;
            if (age < FADE_START_MS) {
                alpha = ALPHA_MAX;
            } else {
                float fadeProgress = (float)(age - FADE_START_MS) / FADE_DURATION_MS;
                alpha = ALPHA_MAX * (1.0f - fadeProgress);
            }
            
            y -= lineHeight;
            if (y < 0) break;
            
            textMgr.DrawString(font, MARGIN_LEFT, y, line.text, 0.5f, 1.0f, 0.5f, alpha);
        }
        
        // Update maxLines based on how many lines fit on screen
        int linesOnScreen = (scrH - MARGIN_BOTTOM) / lineHeight;
        if (linesOnScreen > 10) {
            maxLines = linesOnScreen + 10;
        }
    }
}
