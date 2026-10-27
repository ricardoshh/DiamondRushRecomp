package drpc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Scales the 240x320 phone frame to the window with a selectable filter and background. */
final class Upscaler {

    enum Filter {
        PIXEL("pixel", "Pixel perfect", 1),
        SHARP("sharp", "Nitido", 1),
        SMOOTH("smooth", "Suave", 1),
        SCALE2X("scale2x", "Scale2x", 2),
        SCALE3X("scale3x", "Scale3x", 3),
        SCALE4X("scale4x", "Scale4x", 4);

        final String id;
        final String label;
        final int factor;

        Filter(String id, String label, int factor) {
            this.id = id;
            this.label = label;
            this.factor = factor;
        }

        static Filter fromId(String id) {
            for (Filter f : values()) {
                if (f.id.equalsIgnoreCase(id)) {
                    return f;
                }
            }
            return SHARP;
        }

        Filter next(int dir) {
            Filter[] all = values();
            return all[(ordinal() + dir + all.length) % all.length];
        }
    }

    /** What fills the area around the 3:4 picture (fullscreen / wide windows). */
    enum Background {
        BLACK("black", "Preto"),
        BLUR("blur", "Desfoque do jogo"),
        FRAME("frame", "Moldura");

        final String id;
        final String label;

        Background(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Background fromId(String id) {
            for (Background b : values()) {
                if (b.id.equalsIgnoreCase(id)) {
                    return b;
                }
            }
            return BLUR;
        }

        Background next(int dir) {
            Background[] all = values();
            return all[(ordinal() + dir + all.length) % all.length];
        }
    }

    private static final Color FRAME_GOLD = new Color(0xC9A227);

    private final int srcW;
    private final int srcH;
    private BufferedImage filtered;   // output of ScaleNx
    private BufferedImage prescaled;  // intermediate 2x step of Scale4x
    private BufferedImage sharpBuf;   // nearest-neighbour integer prescale for "sharp" output
    private final BufferedImage[] blurSteps = new BufferedImage[4];

    Upscaler(int srcW, int srcH) {
        this.srcW = srcW;
        this.srcH = srcH;
    }

    /**
     * Draws src into the area (w, h) of g, centered and aspect-correct, over the chosen background.
     * Returns where the picture landed.
     */
    Rectangle draw(Graphics2D g, BufferedImage src, Filter filter, Background bg, int w, int h) {
        Rectangle r = pictureRect(filter, w, h);
        if (bg == Background.FRAME) {
            // leave room for the bezel
            int t = Math.max(6, r.height / 36);
            if (r.height + 2 * t > h && filter != Filter.PIXEL) {
                double s = (double) (h - 2 * t) / r.height;
                r = centered((int) (r.width * s), h - 2 * t, w, h);
            }
        }
        if (bg != Background.BLACK && (r.width < w || r.height < h)) {
            drawBlur(g, src, w, h);
            if (bg == Background.FRAME) {
                drawFrame(g, r);
            }
        }

        if (filter == Filter.PIXEL && r.width % srcW == 0 && r.height % srcH == 0) {
            drawNearest(g, src, r.x, r.y, r.width, r.height);
            return r;
        }
        if (filter == Filter.SMOOTH) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, r.x, r.y, r.width, r.height, null);
            return r;
        }

        BufferedImage img = src;
        if (filter.factor == 2) {
            img = filtered = scale2x(src, filtered);
        } else if (filter.factor == 3) {
            img = filtered = scale3x(src, filtered);
        } else if (filter.factor == 4) {
            prescaled = scale2x(src, prescaled);
            img = filtered = scale2x(prescaled, filtered);
        }
        drawSharp(g, img, r.x, r.y, r.width, r.height);
        return r;
    }

    private Rectangle pictureRect(Filter filter, int w, int h) {
        if (filter == Filter.PIXEL) {
            int k = Math.min(w / srcW, h / srcH);
            if (k >= 1) {
                return centered(srcW * k, srcH * k, w, h);
            }
        }
        double s = Math.min((double) w / srcW, (double) h / srcH);
        return centered((int) Math.round(srcW * s), (int) Math.round(srcH * s), w, h);
    }

    private static Rectangle centered(int dw, int dh, int w, int h) {
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    /** Heavily blurred, darkened copy of the game covering the whole area. */
    private void drawBlur(Graphics2D g, BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        for (int i = 0; i < blurSteps.length; i++) {
            int bw = Math.max(1, cur.getWidth() / 2);
            int bh = Math.max(1, cur.getHeight() / 2);
            blurSteps[i] = target(blurSteps[i], bw, bh);
            Graphics2D sg = blurSteps[i].createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(cur, 0, 0, bw, bh, null);
            sg.dispose();
            cur = blurSteps[i];
        }
        // cover (crop) rather than fit
        double s = Math.max((double) w / cur.getWidth(), (double) h / cur.getHeight());
        int cw = (int) Math.ceil(cur.getWidth() * s);
        int ch = (int) Math.ceil(cur.getHeight() * s);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(cur, (w - cw) / 2, (h - ch) / 2, cw, ch, null);
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, w, h);
    }

    /** A simple handheld-style bezel around the picture. */
    private static void drawFrame(Graphics2D g, Rectangle r) {
        int t = Math.max(6, r.height / 36);
        int arc = t * 3;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(r.x - t - t / 2, r.y - t + t / 2, r.width + 3 * t, r.height + 2 * t + t / 2, arc, arc);
        g.setPaint(new GradientPaint(0, r.y - t, new Color(0x3A3B40), 0, r.y + r.height + t, new Color(0x16171A)));
        g.fillRoundRect(r.x - t, r.y - t, r.width + 2 * t, r.height + 2 * t, arc, arc);
        g.setColor(FRAME_GOLD);
        g.setStroke(new BasicStroke(Math.max(1.5f, t / 4f)));
        g.drawRoundRect(r.x - t / 2, r.y - t / 2, r.width + t, r.height + t, arc / 2, arc / 2);
        g.setColor(Color.BLACK);
        g.fillRect(r.x - 1, r.y - 1, r.width + 2, r.height + 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    /**
     * "Sharp bilinear": nearest-neighbour upscale by the smallest integer that covers the target,
     * then bilinear to the final size. Pixels stay crisp without uneven widths.
     */
    private void drawSharp(Graphics2D g, BufferedImage img, int dx, int dy, int dw, int dh) {
        int iw = img.getWidth();
        int ih = img.getHeight();
        int k = (int) Math.ceil(Math.max((double) dw / iw, (double) dh / ih));
        if (k <= 1) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, dx, dy, dw, dh, null);
            return;
        }
        if (dw == iw * k && dh == ih * k) {
            drawNearest(g, img, dx, dy, dw, dh);
            return;
        }
        BufferedImage big = sharpBuf = target(sharpBuf, iw * k, ih * k);
        Graphics2D bg = big.createGraphics();
        drawNearest(bg, img, 0, 0, iw * k, ih * k);
        bg.dispose();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(big, dx, dy, dw, dh, null);
    }

    private static void drawNearest(Graphics2D g, BufferedImage img, int dx, int dy, int dw, int dh) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, dx, dy, dw, dh, null);
    }

    // ---------------------------------------------------------------- AdvMAME Scale2x / Scale3x

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static BufferedImage target(BufferedImage reuse, int w, int h) {
        if (reuse != null && reuse.getWidth() == w && reuse.getHeight() == h) {
            return reuse;
        }
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    static BufferedImage scale2x(BufferedImage srcImg, BufferedImage reuse) {
        int w = srcImg.getWidth();
        int h = srcImg.getHeight();
        BufferedImage out = target(reuse, w * 2, h * 2);
        int[] s = pixels(srcImg);
        int[] d = pixels(out);
        int ow = w * 2;
        for (int y = 0; y < h; y++) {
            int up = (y > 0 ? y - 1 : y) * w;
            int row = y * w;
            int down = (y < h - 1 ? y + 1 : y) * w;
            int o = y * 2 * ow;
            for (int x = 0; x < w; x++) {
                int xl = x > 0 ? x - 1 : x;
                int xr = x < w - 1 ? x + 1 : x;
                int b = s[up + x];
                int dd = s[row + xl];
                int e = s[row + x];
                int f = s[row + xr];
                int hh = s[down + x];
                int e0 = e;
                int e1 = e;
                int e2 = e;
                int e3 = e;
                if (b != hh && dd != f) {
                    e0 = dd == b ? dd : e;
                    e1 = b == f ? f : e;
                    e2 = dd == hh ? dd : e;
                    e3 = hh == f ? f : e;
                }
                int p = o + x * 2;
                d[p] = e0;
                d[p + 1] = e1;
                d[p + ow] = e2;
                d[p + ow + 1] = e3;
            }
        }
        return out;
    }

    static BufferedImage scale3x(BufferedImage srcImg, BufferedImage reuse) {
        int w = srcImg.getWidth();
        int h = srcImg.getHeight();
        BufferedImage out = target(reuse, w * 3, h * 3);
        int[] s = pixels(srcImg);
        int[] d = pixels(out);
        int ow = w * 3;
        for (int y = 0; y < h; y++) {
            int up = (y > 0 ? y - 1 : y) * w;
            int row = y * w;
            int down = (y < h - 1 ? y + 1 : y) * w;
            int o = y * 3 * ow;
            for (int x = 0; x < w; x++) {
                int xl = x > 0 ? x - 1 : x;
                int xr = x < w - 1 ? x + 1 : x;
                int a = s[up + xl];
                int b = s[up + x];
                int c = s[up + xr];
                int dd = s[row + xl];
                int e = s[row + x];
                int f = s[row + xr];
                int g = s[down + xl];
                int hh = s[down + x];
                int i = s[down + xr];
                int e0 = e;
                int e1 = e;
                int e2 = e;
                int e3 = e;
                int e5 = e;
                int e6 = e;
                int e7 = e;
                int e8 = e;
                if (b != hh && dd != f) {
                    e0 = dd == b ? dd : e;
                    e1 = (dd == b && e != c) || (b == f && e != a) ? b : e;
                    e2 = b == f ? f : e;
                    e3 = (dd == b && e != g) || (dd == hh && e != a) ? dd : e;
                    e5 = (b == f && e != i) || (hh == f && e != c) ? f : e;
                    e6 = dd == hh ? dd : e;
                    e7 = (dd == hh && e != i) || (hh == f && e != g) ? hh : e;
                    e8 = hh == f ? f : e;
                }
                int p = o + x * 3;
                d[p] = e0;
                d[p + 1] = e1;
                d[p + 2] = e2;
                d[p + ow] = e3;
                d[p + ow + 1] = e;
                d[p + ow + 2] = e5;
                d[p + 2 * ow] = e6;
                d[p + 2 * ow + 1] = e7;
                d[p + 2 * ow + 2] = e8;
            }
        }
        return out;
    }
}
