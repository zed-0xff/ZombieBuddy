package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import java.io.File;

/**
 * Loads {@code cursors.json} + its atlas image (same format as {@code window_deco.json}).
 * Tile names: {@code arrow}, {@code resizeH}, {@code resizeW}, {@code resizeNWSE}, {@code resizeNESW},
 * {@code text}, {@code hand}, {@code clock}.
 * Optional per-tile {@code hx}/{@code hy} hotspot; arrow defaults to top-left, others to cell center.
 */
public final class CursorMgr {
    private CursorMgr() {}

    // --- public API ---

    private static final String[] TILE_NAMES = {
        "arrow", "resizeH", "resizeW", "resizeNWSE", "resizeNESW", "text", "hand", "clock"
    };

    /**
     * @return GLFW cursor handles (one per named tile), or {@code null} if JSON/image missing or invalid.
     */
    public static long[] loadCursors(File cursorsJson, Gson gson) {
        Atlas.JsonBase cfg = Atlas.readJson(cursorsJson, gson, Atlas.JsonBase.class);
        if (cfg == null || cfg.image == null || cfg.atlas == null || cfg.tiles == null) {
            return null;
        }
        for (String key : TILE_NAMES) {
            if (cfg.tiles.get(key) == null) {
                return null;
            }
        }

        Atlas atlas = Atlas.load(cursorsJson.getParentFile(), cfg.image, cfg.atlas.width, cfg.atlas.height);
        if (atlas == null) {
            return null;
        }

        long[] out = new long[TILE_NAMES.length];
        for (int i = 0; i < TILE_NAMES.length; i++) {
            Atlas.TileJson tile = cfg.tiles.get(TILE_NAMES[i]);
            if (tile.w < 2 || tile.h < 2 || !atlas.fits(tile)) {
                destroyCreated(out, i);
                return null;
            }
            int hx = metaInt(tile, "hx", defaultHotspotX(i, tile.w));
            int hy = metaInt(tile, "hy", defaultHotspotY(i, tile.h));
            if (hx < 0 || hy < 0 || hx >= tile.w || hy >= tile.h) {
                destroyCreated(out, i);
                return null;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage img = GLFWImage.malloc(stack);
                img.set(tile.w, tile.h, atlas.cellToRgba(tile));
                out[i] = GLFW.glfwCreateCursor(img, hx, hy);
            }
            if (out[i] == 0) {
                destroyCreated(out, i);
                return null;
            }
        }
        return out;
    }

    // --- helpers ---

    /** Arrow (index 0) gets a small top-left hotspot; all others default to cell center. */
    private static int defaultHotspotX(int index, int w) {
        return index == 0 ? Math.min(4, w / 4) : w / 2;
    }

    private static int defaultHotspotY(int index, int h) {
        return index == 0 ? Math.min(4, h / 4) : h / 2;
    }

    private static int metaInt(Atlas.TileJson tile, String key, int fallback) {
        if (tile.metadata == null) return fallback;
        String v = tile.metadata.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static void destroyCreated(long[] cursors, int countExclusive) {
        for (int j = 0; j < countExclusive; j++) {
            if (cursors[j] != 0) {
                GLFW.glfwDestroyCursor(cursors[j]);
            }
        }
    }
}
