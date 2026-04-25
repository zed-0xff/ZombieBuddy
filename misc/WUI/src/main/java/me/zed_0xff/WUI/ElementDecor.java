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
 * 9-slice decoration with "cap corners" layout:
 * - Corner slices keep their own width/height (e.g. 23×23)
 * - Vertical side thickness comes from middleLeft/middleRight width (e.g. 4px)
 * - Top/bottom strip heights come from topCenter/bottomCenter height (e.g. 23px/27px)
 *
 * This matches {@code window.json} where corners are wider than the sides.
 */
final class ElementDecor {
    private final String name;
    private int texture;
    private int atlasW;
    private int atlasH;

    private Tile topLeft, topCenter, topRight;
    private Tile middleLeft, middleCenter, middleRight;
    private Tile bottomLeft, bottomCenter, bottomRight;

    /** Border thickness in screen px (from edge slices). */
    private int leftW, rightW, topH, bottomH;

    /** Corner cap sizes in screen px (from corner slices). */
    private int topLeftW, topRightW, bottomLeftW, bottomRightW;

    /** Distance from element top to text vertical center (px). */
    public int textY = 0;

    public ElementDecor(String name) {
        this.name = name;
        loadFromJson(name);
    }

    public boolean isLoaded() {
        return texture != 0;
    }

    public void dispose() {
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
    }

    public Rect contentRect(int x, int y, int w, int h) {
        // Content box inside the borders (uses edge thickness, not corner cap size).
        return new Rect(
            x + leftW,
            y + topH,
            Math.max(0, w - leftW - rightW),
            Math.max(0, h - topH - bottomH)
        );
    }

    /** Draw decor and fill its content if available; otherwise fill whole rect and outline in black. */
    public void render(int x, int y, int w, int h, Color fill) {
        if (isLoaded()) {
            renderInternal(x, y, w, h);
            Rect c = contentRect(x, y, w, h);
            Element.fillRect(c, fill);
            return;
        }

        Element.fillRect(x, y, w, h, fill);
        Element.outlineRect(x, y, w, h, 1, Color.BLACK);
    }

    void renderInternal(int x, int y, int w, int h) {
        if (texture == 0) {
            return;
        }

        int innerWBySides = Math.max(0, w - leftW - rightW);
        int innerH = Math.max(0, h - topH - bottomH);

        int topMidW = Math.max(0, w - topLeftW - topRightW);
        int botMidW = Math.max(0, w - bottomLeftW - bottomRightW);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor3f(1f, 1f, 1f);

        // Top strip uses corner cap widths
        blit(topLeft, x, y, topLeftW, topH);
        blit(topCenter, x + topLeftW, y, topMidW, topH);
        blit(topRight, x + w - topRightW, y, topRightW, topH);

        // Middle strip uses side thickness (can be thinner than the corners)
        blit(middleLeft, x, y + topH, leftW, innerH);
        blit(middleCenter, x + leftW, y + topH, innerWBySides, innerH);
        blit(middleRight, x + w - rightW, y + topH, rightW, innerH);

        // Bottom strip uses corner cap widths
        blit(bottomLeft, x, y + h - bottomH, bottomLeftW, bottomH);
        blit(bottomCenter, x + bottomLeftW, y + h - bottomH, botMidW, bottomH);
        blit(bottomRight, x + w - bottomRightW, y + h - bottomH, bottomRightW, bottomH);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    // --- loading ---

    private void loadFromJson(String name) {
        File json = resolveJsonFile(name);
        DecorJson cfg = readJson(json);
        if (cfg == null || cfg.image == null || cfg.atlas == null || cfg.tiles == null) {
            warn("json missing image/atlas/tiles: " + (json != null ? json.getPath() : "<null>"));
            return;
        }
        TilesJson t = cfg.tiles;
        if (t.topLeft == null || t.topCenter == null || t.topRight == null
            || t.middleLeft == null || t.middleCenter == null || t.middleRight == null
            || t.bottomLeft == null || t.bottomCenter == null || t.bottomRight == null) {
            warn("json tiles incomplete: " + json.getPath());
            return;
        }
        if (!positiveTile(t.topLeft) || !positiveTile(t.topCenter) || !positiveTile(t.topRight)
            || !positiveTile(t.middleLeft) || !positiveTile(t.middleCenter) || !positiveTile(t.middleRight)
            || !positiveTile(t.bottomLeft) || !positiveTile(t.bottomCenter) || !positiveTile(t.bottomRight)) {
            warn("each tile needs w>=1 and h>=1: " + json.getPath());
            return;
        }

        File png = new File(json.getParentFile() != null ? json.getParentFile() : new File("."), cfg.image);
        BufferedImage img;
        try {
            img = ImageIO.read(png);
        } catch (IOException e) {
            warn("failed reading image: " + png.getPath() + " (" + e.getMessage() + ")");
            return;
        }
        if (img == null) {
            warn("ImageIO returned null for " + png.getPath());
            return;
        }

        atlasW = cfg.atlas.width;
        atlasH = cfg.atlas.height;
        if (atlasW < 1 || atlasH < 1) {
            warn("atlas must be positive in " + json.getPath());
            return;
        }
        if (img.getWidth() != atlasW || img.getHeight() != atlasH) {
            warn("png " + img.getWidth() + "x" + img.getHeight() + " != atlas " + atlasW + "x" + atlasH + " in " + json.getPath());
            return;
        }

        if (!fitsAtlas(t.topLeft) || !fitsAtlas(t.topCenter) || !fitsAtlas(t.topRight)
            || !fitsAtlas(t.middleLeft) || !fitsAtlas(t.middleCenter) || !fitsAtlas(t.middleRight)
            || !fitsAtlas(t.bottomLeft) || !fitsAtlas(t.bottomCenter) || !fitsAtlas(t.bottomRight)) {
            warn("tile rect out of bounds for atlas " + atlasW + "x" + atlasH + " in " + json.getPath());
            return;
        }

        texture = Utils.uploadRgbaTexture2d(img);
        if (texture == 0) {
            warn("failed to upload texture for " + png.getPath());
            return;
        }

        topLeft = Tile.from(t.topLeft);
        topCenter = Tile.from(t.topCenter);
        topRight = Tile.from(t.topRight);
        middleLeft = Tile.from(t.middleLeft);
        middleCenter = Tile.from(t.middleCenter);
        middleRight = Tile.from(t.middleRight);
        bottomLeft = Tile.from(t.bottomLeft);
        bottomCenter = Tile.from(t.bottomCenter);
        bottomRight = Tile.from(t.bottomRight);

        // Border thickness from edges.
        leftW = middleLeft.w;
        rightW = middleRight.w;
        topH = topCenter.h;
        bottomH = bottomCenter.h;

        // Corner caps keep their own widths.
        topLeftW = topLeft.w;
        topRightW = topRight.w;
        bottomLeftW = bottomLeft.w;
        bottomRightW = bottomRight.w;

        textY = cfg.textY;
    }

    private static File resolveJsonFile(String name) {
        return new File(name + ".json");
    }

    private static DecorJson readJson(File jsonFile) {
        if (jsonFile == null || !jsonFile.isFile()) {
            return null;
        }
        try (InputStreamReader r = new InputStreamReader(
            java.nio.file.Files.newInputStream(jsonFile.toPath()), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, DecorJson.class);
        } catch (IOException e) {
            return null;
        }
    }

    // --- drawing helpers ---

    private void blit(Tile r, int sx, int sy, int sw, int sh) {
        if (sw <= 0 || sh <= 0) {
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

    // --- json model ---

    static final class DecorJson {
        String image;
        AtlasJson atlas;
        TilesJson tiles;
        int textY;
    }

    static final class AtlasJson {
        int width;
        int height;
    }

    static final class TilesJson {
        TileJson topLeft, topCenter, topRight;
        TileJson middleLeft, middleCenter, middleRight;
        TileJson bottomLeft, bottomCenter, bottomRight;
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
        static Tile from(TileJson j) { return new Tile(j.x, j.y, j.w, j.h); }
    }

    private static boolean positiveTile(TileJson r) {
        return r.w >= 1 && r.h >= 1;
    }

    private boolean fitsAtlas(TileJson r) {
        return r.x >= 0 && r.y >= 0 && r.x + r.w <= atlasW && r.y + r.h <= atlasH;
    }

    private void warn(String msg) {
        System.err.println("ElementDecor(" + name + "): " + msg);
    }
}

