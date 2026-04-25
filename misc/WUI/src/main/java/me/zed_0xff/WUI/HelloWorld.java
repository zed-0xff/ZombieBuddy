package me.zed_0xff.WUI;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public final class HelloWorld {
    static final int WIN_W = 640;
    static final int WIN_H = 480;

    static int atlasW;
    static int atlasH;
    static FontJson font;
    static Map<Integer, GlyphJson> glyphById = new HashMap<>();
    static Map<Long, Integer> kernAmount = new HashMap<>();
    static GlyphJson spaceGlyph;

    private static final int[] SCALES = {1, 2, 3};
    private static volatile int scaleIdx = 0;

    /** UI scale: ortho and mouse are in logical px; one logical px maps to this many framebuffer px. */
    static int uiScale() {
        return SCALES[scaleIdx % SCALES.length];
    }

    static void renderFrame(long glWindow, int fontTex, Window window) {
        applyFrameProjection(glWindow);

        GL11.glClearColor(Color.GRAY.getRf(), Color.GRAY.getGf(), Color.GRAY.getBf(), 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fontTex);

        int lh = font.face != null ? font.face.lineHeight : 16;
        drawText(32, 32,    "Hello world!");
        drawText(32, 32+lh, "Привет мир!");

        window.render(fontTex);
    }

    public static void main(String[] args) throws IOException {
        File jsonFile = new File("font.json");
        Gson gson = new Gson();
        try (InputStreamReader r = new InputStreamReader(java.nio.file.Files.newInputStream(jsonFile.toPath()))) {
            font = gson.fromJson(r, FontJson.class);
        }
        if (font == null || font.glyphs == null || font.glyphs.isEmpty()) {
            throw new IllegalStateException("no glyphs in " + jsonFile);
        }
        for (GlyphJson g : font.glyphs) {
            glyphById.put(g.id, g);
        }
        spaceGlyph = glyphById.getOrDefault(32, font.glyphs.get(0));
        if (font.kernings != null) {
            for (KerningJson k : font.kernings) {
                kernAmount.put(packPair(k.first, k.second), k.amount);
            }
        }

        File assets = assetDir(jsonFile);
        File pngFile = new File(assets, font.atlas.image);
        if (!pngFile.isFile()) {
            throw new IllegalStateException("atlas image not found: " + pngFile);
        }

        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        long glWindow = GLFW.glfwCreateWindow(WIN_W, WIN_H, "Desktop", 0, 0);
        if (glWindow == 0) throw new RuntimeException("glfwCreateWindow failed");

        GLFW.glfwMakeContextCurrent(glWindow);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
        initGlState();

        File cursorsJson = new File(assets, "cursors.json");
        Window.createCursors(cursorsJson, gson);

        File windowJson = new File(assets, "window_deco.json");
        Window window = new Window(80, 48, 420, 260, "Window");
        window.addControl(new Button(10, 10, 100, 20, "OK"));

        GLFW.glfwSetMouseButtonCallback(glWindow, (win, button, action, mods) -> {
            double[] cx = new double[1];
            double[] cy = new double[1];
            GLFW.glfwGetCursorPos(win, cx, cy);
            double[] f = cursorToFramebuffer(win, cx[0], cy[0]);
            int scale = uiScale();
            int lx = (int)(f[0] / scale);
            int ly = (int)(f[1] / scale);
            boolean startedDrag = window.handleMouseButton(win, button, action, lx, ly);
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && action == GLFW.GLFW_PRESS
                && !startedDrag
                && !window.contains(lx, ly)) {
                scaleIdx = (scaleIdx + 1) % SCALES.length;
                int ui = uiScale();
                GLFW.glfwSetWindowTitle(win, "Desktop — " + ui + "x");
            }
        });

        GLFW.glfwSetCursorPosCallback(glWindow, (win, xpos, ypos) -> {
            double[] f = cursorToFramebuffer(win, xpos, ypos);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer fbw = stack.mallocInt(1);
                IntBuffer fbh = stack.mallocInt(1);
                GLFW.glfwGetFramebufferSize(win, fbw, fbh);
                int scale = uiScale();
                int vw = fbw.get(0) / scale;
                int vh = fbh.get(0) / scale;
                window.handleCursorPos(win, (int)(f[0] / scale), (int)(f[1] / scale), vw, vh);
            }
        });

        GLFW.glfwSetFramebufferSizeCallback(glWindow, (win, w, h) -> applyFrameProjection(win));

        int fontTex = loadTexture(pngFile);
        GLFW.glfwSetWindowTitle(glWindow, "Desktop — " + uiScale() + "x");

        GLFW.glfwSetWindowRefreshCallback(glWindow, win -> {
            renderFrame(win, fontTex, window);
            GLFW.glfwSwapBuffers(win);
        });

        while (!GLFW.glfwWindowShouldClose(glWindow)) {
            renderFrame(glWindow, fontTex, window);
            GLFW.glfwSwapBuffers(glWindow);
            GLFW.glfwPollEvents();
        }

        GL11.glDeleteTextures(fontTex);
        GLFW.glfwSetCursor(glWindow, 0);
        Window.destroyCursors();
        GLFW.glfwDestroyWindow(glWindow);
        GLFW.glfwTerminate();
    }

    /**
     * Folder that holds {@code font.json} and sibling files ({@code font.png}, {@code cursors.json},
     * {@code window_deco.json}, etc.). Uses the font file’s absolute parent so a bare {@code font.json}
     * path still resolves next to the process working directory.
     */
    static File assetDir(File fontJson) {
        File abs = fontJson.getAbsoluteFile();
        File parent = abs.getParentFile();
        return parent != null ? parent : new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    /** One-time GL state; projection matches framebuffer each frame (HiDPI). */
    static void initGlState() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Viewport + ortho in framebuffer pixels (fixes 0.5x look on Retina until resize). */
    static void applyFrameProjection(long glWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(glWindow, fbw, fbh);
            int w = Math.max(1, fbw.get(0));
            int h = Math.max(1, fbh.get(0));
            int scale = uiScale();
            GL11.glViewport(0, 0, w, h);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, w / scale, h / scale, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
        }
    }

    /** Map glfwGetCursorPos (glWindow coords) to framebuffer pixel coords used by glOrtho. */
    static double[] cursorToFramebuffer(long glWindow, double cxWin, double cyWin) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer winW = stack.mallocInt(1);
            IntBuffer winH = stack.mallocInt(1);
            IntBuffer fbw  = stack.mallocInt(1);
            IntBuffer fbh  = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(glWindow, winW, winH);
            GLFW.glfwGetFramebufferSize(glWindow, fbw, fbh);
            int ww = winW.get(0);
            int wh = winH.get(0);
            int fw = fbw.get(0);
            int fh = fbh.get(0);
            if (ww <= 0 || wh <= 0) {
                return new double[] {cxWin, cyWin};
            }
            return new double[] {cxWin * fw / ww, cyWin * fh / wh};
        }
    }

    static int loadTexture(File path) {
        BufferedImage img;
        try {
            img = ImageIO.read(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (img == null) {
            throw new RuntimeException("ImageIO.read failed: " + path);
        }

        atlasW = img.getWidth();
        atlasH = img.getHeight();
        if (font.atlas.width != atlasW || font.atlas.height != atlasH) {
            System.err.println("warn: atlas size json " + font.atlas.width + "x" + font.atlas.height
                + " != png " + atlasW + "x" + atlasH);
        }

        return uploadRgbaTexture2d(img);
    }

    /**
     * @return texture id, or {@code 0} if missing/unreadable
     */
    static int loadTexture2d(File path) {
        BufferedImage img;
        try {
            img = ImageIO.read(path);
        } catch (IOException e) {
            return 0;
        }
        if (img == null) {
            return 0;
        }
        return uploadRgbaTexture2d(img);
    }

    static int uploadRgbaTexture2d(BufferedImage img) {
        int tw = img.getWidth();
        int th = img.getHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(tw * th * 4);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int argb = img.getRGB(x, y);
                pixels.put((byte) ((argb >> 16) & 0xff));
                pixels.put((byte) ((argb >> 8) & 0xff));
                pixels.put((byte) (argb & 0xff));
                pixels.put((byte) ((argb >> 24) & 0xff));
            }
        }
        pixels.flip();

        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            tw,
            th,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            pixels
        );

        return tex;
    }

    /** Horizontal advance of the first line, in font pixels (same rules as {@link #drawText}). */
    static int measureTextAdvancePx(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int relX = 0;
        int prevCp = -1;
        for (int off = 0; off < s.length();) {
            int cp = s.codePointAt(off);
            off += Character.charCount(cp);
            if (cp == '\n') {
                break;
            }
            if (prevCp >= 0) {
                Integer k = kernAmount.get(packPair(prevCp, cp));
                if (k != null) {
                    relX += k;
                }
            }
            GlyphJson g = glyphById.getOrDefault(cp, spaceGlyph);
            relX += g.xa;
            prevCp = cp;
        }
        return relX;
    }

    /**
     * @param y of the first line’s top (glWindow coords, origin top-left).
     * @param scale integer scale factor (font pixels → screen pixels).
     */
    static void drawText(int x, int y, String s) {
        int scale = 1;
        int relX = 0;
        int relLineY = 0;
        int prevCp = -1;
        int lineSkip = font.face != null ? font.face.lineHeight : 16;

        GL11.glBegin(GL11.GL_QUADS);

        for (int off = 0; off < s.length();) {
            int cp = s.codePointAt(off);
            off += Character.charCount(cp);

            if (cp == '\n') {
                relX = 0;
                relLineY += lineSkip;
                prevCp = -1;
                continue;
            }

            if (prevCp >= 0) {
                Integer k = kernAmount.get(packPair(prevCp, cp));
                if (k != null) {
                    relX += k;
                }
            }

            GlyphJson g = glyphById.getOrDefault(cp, spaceGlyph);
            if (g.w > 0 && g.h > 0) {
                float sx = x + (relX + g.xo) * scale;
                float sy = y + (relLineY + g.yo) * scale;
                float x1 = sx + g.w * scale;
                float y1 = sy + g.h * scale;

                float u0 = g.x / (float) atlasW;
                float u1 = (g.x + g.w) / (float) atlasW;
                float v0 = g.y / (float) atlasH;
                float v1 = (g.y + g.h) / (float) atlasH;

                GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(sx, sy);
                GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x1, sy);
                GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x1, y1);
                GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(sx, y1);
            }

            relX += g.xa;
            prevCp = cp;
        }

        GL11.glEnd();
    }

    static long packPair(int first, int second) {
        return ((long) first << 32) | (second & 0xffffffffL);
    }

    // --- GSON model (glyphs use short keys: w, h, xo, yo, xa) ---

    static final class FontJson {
        AtlasJson atlas;
        FaceJson face;
        List<GlyphJson> glyphs;
        List<KerningJson> kernings;
    }

    static final class AtlasJson {
        int width, height;
        String image;
    }

    static final class FaceJson {
        String family;
        int size;
        int bold;
        int italic;
        int lineHeight;
        int base;
        int[] padding;
        int[] spacing;
    }

    static final class GlyphJson {
        int id, x, y, w, h, xo, yo, xa;
    }

    static final class KerningJson {
        int first, second, amount;
    }
}
