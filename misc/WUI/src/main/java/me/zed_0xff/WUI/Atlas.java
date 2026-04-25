package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * Loaded image atlas shared by {@link ElementDecor} and {@link CursorMgr}.
 * Handles PNG loading, atlas-size validation, tile bounds checking,
 * GL texture upload, and RGBA cell extraction.
 */
final class Atlas {
    final BufferedImage img;
    final int w, h;

    private Atlas(BufferedImage img, int w, int h) {
        this.img = img;
        this.w   = w;
        this.h   = h;
    }

    /**
     * Load a PNG and verify it matches the declared atlas dimensions.
     * @param dir       directory that contains the image (may be null → use CWD)
     * @param imageName file name relative to {@code dir}
     * @return loaded Atlas, or {@code null} on any error (error printed to stderr)
     */
    static Atlas load(File dir, String imageName, int atlasW, int atlasH) {
        if (atlasW < 1 || atlasH < 1) {
            warn("invalid size " + atlasW + "x" + atlasH);
            return null;
        }
        File png = new File(dir != null ? dir : new File("."), imageName);
        BufferedImage img;
        try {
            img = ImageIO.read(png);
        } catch (IOException e) {
            warn("failed reading " + png.getPath() + ": " + e.getMessage());
            return null;
        }
        if (img == null) {
            warn("ImageIO returned null for " + png.getPath());
            return null;
        }
        if (img.getWidth() != atlasW || img.getHeight() != atlasH) {
            warn("png " + img.getWidth() + "x" + img.getHeight()
                + " != declared " + atlasW + "x" + atlasH + " (" + png.getPath() + ")");
            return null;
        }
        return new Atlas(img, atlasW, atlasH);
    }

    /** True if {@code t} has positive dimensions and lies entirely within the atlas. */
    boolean fits(TileJson t) {
        return t != null && t.w >= 1 && t.h >= 1
            && t.x >= 0 && t.y >= 0
            && t.x + t.w <= w && t.y + t.h <= h;
    }

    /** Upload the atlas image as an OpenGL texture and return the texture ID (0 on failure). */
    int uploadTexture() {
        return Utils.uploadRgbaTexture2d(img);
    }

    /** Extract one tile cell as an RGBA8888 {@link ByteBuffer}, top-row first (GLFW / GL convention). */
    ByteBuffer cellToRgba(TileJson t) {
        ByteBuffer buf = BufferUtils.createByteBuffer(t.w * t.h * 4);
        for (int y = 0; y < t.h; y++) {
            for (int x = 0; x < t.w; x++) {
                int argb = img.getRGB(t.x + x, t.y + y);
                buf.put((byte) ((argb >> 16) & 0xff));
                buf.put((byte) ((argb >> 8)  & 0xff));
                buf.put((byte) (argb         & 0xff));
                buf.put((byte) ((argb >> 24) & 0xff));
            }
        }
        buf.flip();
        return buf;
    }

    /** Parse a JSON file into an object of type {@code cls}. Returns {@code null} on any error. */
    static <T> T readJson(File jsonFile, Gson gson, Class<T> cls) {
        if (jsonFile == null || !jsonFile.isFile() || gson == null) {
            return null;
        }
        try (InputStreamReader r = new InputStreamReader(
                java.nio.file.Files.newInputStream(jsonFile.toPath()), StandardCharsets.UTF_8)) {
            return gson.fromJson(r, cls);
        } catch (IOException e) {
            return null;
        }
    }

    // --- shared JSON model ---

    static class JsonBase {
        String image;
        SizeJson atlas;
        java.util.Map<String, TileJson> tiles;
        java.util.Map<String, String> metadata;
    }

    static final class SizeJson {
        int width, height;
    }

    static class TileJson {
        int x, y, w, h;
        java.util.Map<String, String> metadata;
    }

    private static void warn(String msg) {
        System.err.println("Atlas: " + msg);
    }
}
