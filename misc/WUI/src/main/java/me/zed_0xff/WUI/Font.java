package me.zed_0xff.WUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import com.google.gson.Gson;

class Font {
    Map<Integer, GlyphJson> glyphById = new HashMap<>();
    Map<Long, Integer> kernAmount = new HashMap<>();
    GlyphJson spaceGlyph;
    int atlasW;
    int atlasH;
    int fontTex;
    FaceJson face;

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

    public Font() {
        File jsonFile = new File("font.json");
        Gson gson = new Gson();
        FontJson cfg;
        try (InputStreamReader r = new InputStreamReader(Files.newInputStream(jsonFile.toPath()))) {
            cfg = gson.fromJson(r, FontJson.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed reading json: " + e.getMessage());
        }
        if (cfg == null || cfg.glyphs == null || cfg.glyphs.isEmpty()) {
            throw new IllegalStateException("no glyphs in " + jsonFile);
        }

        File pngFile = new File(cfg.atlas.image);
        if (!pngFile.isFile()) {
            throw new IllegalStateException("atlas image not found: " + pngFile);
        }

        for (GlyphJson g : cfg.glyphs) {
            glyphById.put(g.id, g);
        }
        spaceGlyph = glyphById.getOrDefault(32, cfg.glyphs.get(0));
        if (cfg.kernings != null) {
            for (KerningJson k : cfg.kernings) {
                kernAmount.put(Utils.packPair(k.first, k.second), k.amount);
            }
        }
        face = cfg.face;
        fontTex = loadTexture(pngFile, cfg.atlas);
    }

    int loadTexture(File path, AtlasJson atlas) {
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
        if (atlas.width != atlasW || atlas.height != atlasH) {
            System.err.println("warn: atlas size json " + atlas.width + "x" + atlas.height
                + " != png " + atlasW + "x" + atlasH);
        }

        return Utils.uploadRgbaTexture2d(img);
    }

    /** Horizontal advance of the first line, in font pixels (same rules as {@link #drawText}). */
    int measureTextAdvancePx(String s) {
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
                Integer k = kernAmount.get(Utils.packPair(prevCp, cp));
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
    void drawText(int x, int y, String s) {
        int scale = 1;
        int relX = 0;
        int relLineY = 0;
        int prevCp = -1;
        int lineSkip = face.lineHeight;

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
                Integer k = kernAmount.get(Utils.packPair(prevCp, cp));
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
}
