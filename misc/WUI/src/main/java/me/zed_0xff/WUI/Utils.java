package me.zed_0xff.WUI;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

class Utils {
    static long packPair(int first, int second) {
        return ((long) first << 32) | (second & 0xffffffffL);
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
}
