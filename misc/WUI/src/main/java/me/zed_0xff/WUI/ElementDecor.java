package me.zed_0xff.WUI;

import com.google.gson.Gson;
import org.lwjgl.opengl.GL11;

import java.io.File;

/**
 * 9-slice decoration with "cap corners" layout:
 * - Corner slices keep their own width/height (e.g. 23×23)
 * - Vertical side thickness comes from middleLeft/middleRight width (e.g. 4px)
 * - Top/bottom strip heights come from topCenter/bottomCenter height (e.g. 23px/27px)
 *
 * This matches {@code window_deco.json} where corners are wider than the sides.
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
    /** Distance from element left to text left edge (px). 0 = horizontally centered. */
    public int textX = 0;

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
            Element.fillRect(contentRect(x, y, w, h), fill);
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
        int innerH        = Math.max(0, h - topH - bottomH);
        int topMidW       = Math.max(0, w - topLeftW - topRightW);
        int botMidW       = Math.max(0, w - bottomLeftW - bottomRightW);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor3f(1f, 1f, 1f);

        blit(topLeft,    x,              y,              topLeftW,      topH);
        blit(topCenter,  x + topLeftW,   y,              topMidW,       topH);
        blit(topRight,   x + w - topRightW, y,           topRightW,     topH);

        blit(middleLeft,   x,            y + topH, leftW,          innerH);
        blit(middleCenter, x + leftW,    y + topH, innerWBySides,  innerH);
        blit(middleRight,  x + w - rightW, y + topH, rightW,       innerH);

        blit(bottomLeft,   x,                 y + h - bottomH, bottomLeftW,  bottomH);
        blit(bottomCenter, x + bottomLeftW,   y + h - bottomH, botMidW,      bottomH);
        blit(bottomRight,  x + w - bottomRightW, y + h - bottomH, bottomRightW, bottomH);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    // --- loading ---

    private void loadFromJson(String name) {
        File json = new File(name + ".json");
        Atlas.JsonBase cfg = Atlas.readJson(json, new Gson(), Atlas.JsonBase.class);
        if (cfg == null || cfg.image == null || cfg.atlas == null || cfg.tiles == null) {
            warn("json missing image/atlas/tiles: " + json.getPath());
            return;
        }

        String[] required = {
            "topLeft", "topCenter", "topRight",
            "middleLeft", "middleCenter", "middleRight",
            "bottomLeft", "bottomCenter", "bottomRight"
        };
        for (String key : required) {
            if (cfg.tiles.get(key) == null) {
                warn("missing tile \"" + key + "\" in " + json.getPath());
                return;
            }
        }

        Atlas atlas = Atlas.load(json.getParentFile(), cfg.image, cfg.atlas.width, cfg.atlas.height);
        if (atlas == null) {
            warn("atlas load failed for " + json.getPath());
            return;
        }

        for (String key : required) {
            if (!atlas.fits(cfg.tiles.get(key))) {
                warn("tile \"" + key + "\" out of bounds or non-positive in " + json.getPath());
                return;
            }
        }

        texture = atlas.uploadTexture();
        if (texture == 0) {
            warn("failed to upload texture for " + json.getPath());
            return;
        }

        atlasW = atlas.w;
        atlasH = atlas.h;

        topLeft      = Tile.from(cfg.tiles.get("topLeft"));
        topCenter    = Tile.from(cfg.tiles.get("topCenter"));
        topRight     = Tile.from(cfg.tiles.get("topRight"));
        middleLeft   = Tile.from(cfg.tiles.get("middleLeft"));
        middleCenter = Tile.from(cfg.tiles.get("middleCenter"));
        middleRight  = Tile.from(cfg.tiles.get("middleRight"));
        bottomLeft   = Tile.from(cfg.tiles.get("bottomLeft"));
        bottomCenter = Tile.from(cfg.tiles.get("bottomCenter"));
        bottomRight  = Tile.from(cfg.tiles.get("bottomRight"));

        leftW        = middleLeft.w;
        rightW       = middleRight.w;
        topH         = topCenter.h;
        bottomH      = bottomCenter.h;

        topLeftW     = topLeft.w;
        topRightW    = topRight.w;
        bottomLeftW  = bottomLeft.w;
        bottomRightW = bottomRight.w;

        if (cfg.metadata != null) {
            String v = cfg.metadata.get("textY");
            if (v != null) {
                try { textY = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            }
            v = cfg.metadata.get("textX");
            if (v != null) {
                try { textX = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            }
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
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(sx,      sy);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(sx + sw, sy);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(sx + sw, sy + sh);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(sx,      sy + sh);
        GL11.glEnd();
    }

    private static final class Tile {
        final int x, y, w, h;
        Tile(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        static Tile from(Atlas.TileJson j) { return new Tile(j.x, j.y, j.w, j.h); }
    }

    private void warn(String msg) {
        System.err.println("ElementDecor(" + name + "): " + msg);
    }
}
