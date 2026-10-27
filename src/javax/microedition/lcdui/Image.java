package javax.microedition.lcdui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

public class Image {

    final BufferedImage image;
    private final boolean mutable;

    private Image(BufferedImage image, boolean mutable) {
        this.image = image;
        this.mutable = mutable;
    }

    public static Image createImage(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException();
        }
        // Mutable images are opaque and start out white.
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return new Image(img, true);
    }

    public static Image createImage(String name) throws IOException {
        InputStream in = drpc.Runtime.openResource(name);
        if (in == null) {
            throw new IOException("resource not found: " + name);
        }
        try {
            return createImage(in);
        } finally {
            in.close();
        }
    }

    public static Image createImage(InputStream in) throws IOException {
        return new Image(decode(in.readAllBytes()), false);
    }

    public static Image createImage(byte[] data, int offset, int length) {
        try {
            return new Image(decode(java.util.Arrays.copyOfRange(data, offset, offset + length)), false);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    public static Image createImage(Image source) {
        if (!source.mutable) {
            return source;
        }
        return new Image(toArgb(source.image), false);
    }

    public static Image createRGBImage(int[] rgb, int w, int h, boolean processAlpha) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (processAlpha) {
            img.setRGB(0, 0, w, h, rgb, 0, w);
        } else {
            int[] opaque = new int[w * h];
            for (int i = 0; i < opaque.length; i++) {
                opaque[i] = rgb[i] | 0xFF000000;
            }
            img.setRGB(0, 0, w, h, opaque, 0, w);
        }
        return new Image(img, false);
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IOException("unsupported image data (" + bytes.length + " bytes)");
        }
        return toArgb(img);
    }

    private static BufferedImage toArgb(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    public Graphics getGraphics() {
        if (!mutable) {
            throw new IllegalStateException("immutable image");
        }
        return Graphics.forImage(image);
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public boolean isMutable() {
        return mutable;
    }

    public void getRGB(int[] rgb, int offset, int scanlength, int x, int y, int w, int h) {
        image.getRGB(x, y, w, h, rgb, offset, scanlength);
    }
}
