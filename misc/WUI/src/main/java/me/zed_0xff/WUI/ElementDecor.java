package me.zed_0xff.WUI;

import com.google.gson.Gson;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * {@code window_deco.json} + image named by its {@code image} field (e.g. {@code window_deco.png}): nine-patch frame. Screen layout (fixed):
 * top strip 23px, bottom 26px, vertical sides 4px, top corners 23×23, bottom corners 23×26.
 * Atlas tile rects are read from json (must lie inside {@code atlas}); on-screen frame uses {@link #FRAME_TOP_H},
 * {@link #FRAME_BOTTOM_H}, {@link #FRAME_SIDE_W}, and corner widths in {@link #draw} — slice pixel sizes may differ.
 */
public final class ElementDecor {
    static final int FRAME_TOP_H     = 23;
    static final int FRAME_BOTTOM_H  = 27;
    static final int FRAME_SIDE_W    = 4;
    static final int TOP_CORNER      = 23;
    static final int BOTTOM_CORNER_W = 23;

    public final int texture;
    public final int atlasW;
    public final int atlasH;
    private final Tile topLeft;
    private final Tile topCenter;
    private final Tile topRight;
    private final Tile middleLeft;
    private final Tile middleCenter;
    private final Tile middleRight;
    private final Tile bottomLeft;
    private final Tile bottomCenter;
    private final Tile bottomRight;

    private ElementDecor(
        int texture,
        int atlasW,
        int atlasH,
        Tile topLeft,
        Tile topCenter,
        Tile topRight,
        Tile middleLeft,
        Tile middleCenter,
        Tile middleRight,
        Tile bottomLeft,
        Tile bottomCenter,
        Tile bottomRight
    ) {
        this.texture      = texture;
        this.atlasW       = atlasW;
        this.atlasH       = atlasH;
        this.topLeft      = topLeft;
        this.topCenter    = topCenter;
        this.topRight     = topRight;
        this.middleLeft   = middleLeft;
        this.middleCenter = middleCenter;
        this.middleRight  = middleRight;
        this.bottomLeft   = bottomLeft;
        this.bottomCenter = bottomCenter;
        this.bottomRight  = bottomRight;
    }

    public void dispose() {
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
        }
    }

    /** Inner client rect (inside frame), same coords as previous solid fill. */
    public int contentX(int wx) {
        return wx + FRAME_SIDE_W;
    }

    public int contentY(int wy) {
        return wy + FRAME_TOP_H;
    }

    public int contentW(int ww) {
        return Math.max(0, ww - 2 * FRAME_SIDE_W);
    }

    public int contentH(int wh) {
        return Math.max(0, wh - FRAME_TOP_H - FRAME_BOTTOM_H);
    }

    public void draw(int wx, int wy, int ww, int wh) {
        int c = TOP_CORNER;
        int b = FRAME_BOTTOM_H;
        int s = FRAME_SIDE_W;
        int t = FRAME_TOP_H;
        int innerH = Math.max(0, wh - t - b);
        int topMidW = Math.max(0, ww - 2 * c);
        int botMidW = topMidW;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor3f(1f, 1f, 1f);

        blit(topLeft, wx, wy, c, t);
        blit(topCenter, wx + c, wy, topMidW, t);
        blit(topRight, wx + ww - c, wy, c, t);

        blit(middleLeft, wx, wy + t, s, innerH);
        blit(middleCenter, wx + s, wy + t, Math.max(0, ww - 2 * s), innerH);
        blit(middleRight, wx + ww - s, wy + t, s, innerH);

        blit(bottomLeft, wx, wy + wh - b, BOTTOM_CORNER_W, b);
        blit(bottomCenter, wx + BOTTOM_CORNER_W, wy + wh - b, botMidW, b);
        blit(bottomRight, wx + ww - BOTTOM_CORNER_W, wy + wh - b, BOTTOM_CORNER_W, b);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private void blit(Tile r, int sx, int sy, int sw, int sh) {
        if (sw <= 0f || sh <= 0f) {
            return;
        }
        float u0 = r.x / (float) atlasW;
        float v0 = r.y / (float) atlasH;
        float u1 = (r.x + r.w) / (float) atlasW;
        float v1 = (r.y + r.h) / (float) atlasH;

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(sx, sy);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(sx + sw, sy);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(sx + sw, sy + sh);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(sx, sy + sh);
        GL11.glEnd();
    }

    static final class ElementDecorJson {
        String image;
        AtlasJson atlas;
        TilesJson tiles;
    }

    static final class AtlasJson {
        int width;
        int height;
    }

    static final class TilesJson {
        TileJson topLeft;
        TileJson topCenter;
        TileJson topRight;
        TileJson middleLeft;
        TileJson middleCenter;
        TileJson middleRight;
        TileJson bottomLeft;
        TileJson bottomCenter;
        TileJson bottomRight;
    }

    static final class TileJson {
        int x, y, w, h;
    }

    private static final class Tile {
        final int x, y, w, h;

        Tile(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        static Tile from(TileJson j) {
            return new Tile(j.x, j.y, j.w, j.h);
        }
    }

    public static ElementDecor load(File jsonFile, Gson gson) {
        if (jsonFile == null || gson == null) {
            warn("no json path or gson");
            return null;
        }
        if (!jsonFile.isFile()) {
            warn("json not found: " + jsonFile.getAbsolutePath());
            return null;
        }
        ElementDecorJson cfg;
        try (InputStreamReader r = new InputStreamReader(
            java.nio.file.Files.newInputStream(jsonFile.toPath()), StandardCharsets.UTF_8)) {
            cfg = gson.fromJson(r, ElementDecorJson.class);
        } catch (IOException e) {
            warn("failed reading json: " + e.getMessage());
            return null;
        }
        if (cfg == null || cfg.image == null || cfg.atlas == null || cfg.tiles == null) {
            warn("json missing image, atlas, or tiles");
            return null;
        }
        TilesJson t = cfg.tiles;
        if (t.topLeft == null || t.topCenter == null || t.topRight == null
            || t.middleLeft == null || t.middleCenter == null || t.middleRight == null
            || t.bottomLeft == null || t.bottomCenter == null || t.bottomRight == null) {
            warn("json tiles section incomplete (need all nine keys)");
            return null;
        }
        if (!positiveTile(t.topLeft) || !positiveTile(t.topCenter) || !positiveTile(t.topRight)
            || !positiveTile(t.middleLeft) || !positiveTile(t.middleCenter) || !positiveTile(t.middleRight)
            || !positiveTile(t.bottomLeft) || !positiveTile(t.bottomCenter) || !positiveTile(t.bottomRight)) {
            warn("each tile needs w>=1 and h>=1 in json");
            return null;
        }
        File png = new File(HelloWorld.assetDir(jsonFile), cfg.image);
        if (!png.isFile()) {
            warn("image not found (next to font json): " + png.getAbsolutePath());
            return null;
        }
        BufferedImage img;
        try {
            img = ImageIO.read(png);
        } catch (IOException e) {
            warn("failed reading image: " + e.getMessage());
            return null;
        }
        if (img == null) {
            warn("ImageIO returned null for " + png.getAbsolutePath());
            return null;
        }
        int aw = cfg.atlas.width;
        int ah = cfg.atlas.height;
        if (aw < 1 || ah < 1 || img.getWidth() != aw || img.getHeight() != ah) {
            warn("png size " + img.getWidth() + "x" + img.getHeight() + " != atlas in json " + aw + "x" + ah
                + " — update window_deco.json \"atlas\" or resize the png");
            return null;
        }
        int tex = HelloWorld.uploadRgbaTexture2d(img);
        if (!fitsAtlas(t.topLeft, aw, ah) || !fitsAtlas(t.topCenter, aw, ah) || !fitsAtlas(t.topRight, aw, ah)
            || !fitsAtlas(t.middleLeft, aw, ah) || !fitsAtlas(t.middleCenter, aw, ah) || !fitsAtlas(t.middleRight, aw, ah)
            || !fitsAtlas(t.bottomLeft, aw, ah) || !fitsAtlas(t.bottomCenter, aw, ah) || !fitsAtlas(t.bottomRight, aw, ah)) {
            GL11.glDeleteTextures(tex);
            warn("a tile rect extends outside atlas " + aw + "x" + ah);
            return null;
        }
        return new ElementDecor(
            tex,
            aw,
            ah,
            Tile.from(t.topLeft),
            Tile.from(t.topCenter),
            Tile.from(t.topRight),
            Tile.from(t.middleLeft),
            Tile.from(t.middleCenter),
            Tile.from(t.middleRight),
            Tile.from(t.bottomLeft),
            Tile.from(t.bottomCenter),
            Tile.from(t.bottomRight)
        );
    }

    private static boolean positiveTile(TileJson r) {
        return r.w >= 1 && r.h >= 1;
    }

    private static boolean fitsAtlas(TileJson r, int aw, int ah) {
        return r.x >= 0 && r.y >= 0 && r.x + r.w <= aw && r.y + r.h <= ah;
    }

    private static void warn(String msg) {
        System.err.println("ElementDecor: " + msg);
    }
}
