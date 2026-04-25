package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Loads {@code cursors.json} + {@link CursorsJson#image} beside it (same pattern as {@code font.json} + atlas).
 * Cursors in order: arrow, horizontal resize, vertical resize, NW–SE, NE–SW.
 * Each entry: {@code x},{@code y},{@code w},{@code h}; omit {@code x}/{@code y} to pack left-to-right on one row.
 * Optional {@code hx}/{@code hy}; if omitted, arrow uses a small top-left hotspot, others use cell center.
 */
public final class CursorMgr {
    public static final int CURSOR_COUNT = 8;

    private CursorMgr() {
    }

    static final class CursorsJson {
        String image;
        List<CursorRectJson> cursors;
    }

    static final class CursorRectJson {
        Integer x,y;
        int w,h;
        Integer hx,hy;
    }

    /**
     * @return five GLFW cursor handles, or {@code null} if JSON/image missing or invalid
     */
    public static long[] loadCursors(File cursorsJson, Gson gson) {
        if (cursorsJson == null || !cursorsJson.isFile() || gson == null) {
            return null;
        }
        CursorsJson cfg;
        try (InputStreamReader r = new InputStreamReader(
            java.nio.file.Files.newInputStream(cursorsJson.toPath()), StandardCharsets.UTF_8)) {
            cfg = gson.fromJson(r, CursorsJson.class);
        } catch (IOException e) {
            return null;
        }
        if (cfg == null || cfg.image == null || cfg.cursors == null || cfg.cursors.size() != CURSOR_COUNT) {
            return null;
        }
        File png = new File(cursorsJson.getParentFile(), cfg.image);
        if (!png.isFile()) {
            return null;
        }
        BufferedImage sheet;
        try {
            sheet = ImageIO.read(png);
        } catch (IOException e) {
            return null;
        }
        if (sheet == null) {
            return null;
        }
        int sw = sheet.getWidth();
        int sh = sheet.getHeight();

        long[] out = new long[CURSOR_COUNT];
        int runX = 0;
        for (int i = 0; i < CURSOR_COUNT; i++) {
            CursorRectJson cr = cfg.cursors.get(i);
            if (cr.w < 2 || cr.h < 2) {
                destroyCreated(out, i);
                return null;
            }
            int x0 = cr.x != null ? cr.x : runX;
            int y0 = cr.y != null ? cr.y : 0;
            if (x0 < 0 || y0 < 0 || x0 + cr.w > sw || y0 + cr.h > sh) {
                destroyCreated(out, i);
                return null;
            }
            if (cr.x == null) {
                runX = x0 + cr.w;
            } else {
                runX = Math.max(runX, x0 + cr.w);
            }
            int hx = cr.hx != null ? cr.hx : defaultHotspotX(i, cr.w);
            int hy = cr.hy != null ? cr.hy : defaultHotspotY(i, cr.h);
            if (hx < 0 || hy < 0 || hx >= cr.w || hy >= cr.h) {
                destroyCreated(out, i);
                return null;
            }
            ByteBuffer rgba = cellToRgba(sheet, x0, y0, cr.w, cr.h);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage img = GLFWImage.malloc(stack);
                img.set(cr.w, cr.h, rgba);
                out[i] = GLFW.glfwCreateCursor(img, hx, hy);
            }
            if (out[i] == 0) {
                destroyCreated(out, i);
                return null;
            }
        }
        return out;
    }

    private static int defaultHotspotX(int index, int w) {
        if (index == 0) {
            return Math.min(4, w / 4);
        }
        return w / 2;
    }

    private static int defaultHotspotY(int index, int h) {
        if (index == 0) {
            return Math.min(4, h / 4);
        }
        return h / 2;
    }

    private static void destroyCreated(long[] cursors, int countExclusive) {
        for (int j = 0; j < countExclusive; j++) {
            if (cursors[j] != 0) {
                GLFW.glfwDestroyCursor(cursors[j]);
            }
        }
    }

    /** RGBA8888, top row first (GLFW convention). */
    private static ByteBuffer cellToRgba(BufferedImage src, int x0, int y0, int w, int h) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x0 + x, y0 + y);
                buf.put((byte) ((argb >> 16) & 0xff));
                buf.put((byte) ((argb >> 8) & 0xff));
                buf.put((byte) (argb & 0xff));
                buf.put((byte) ((argb >> 24) & 0xff));
            }
        }
        buf.flip();
        return buf;
    }
}
