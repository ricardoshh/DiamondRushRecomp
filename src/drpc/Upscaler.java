package drpc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** Image filters and backgrounds, picture layout in the window, and the bezel. The filters run on the GPU (GLWindow). */
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


    /**
     * Where the picture goes in a (w, h) window: centered, aspect-correct, integer scale for
     * "pixel", leaving room for the bezel with the "frame" background.
     */
    static Rectangle layout(Filter filter, Background bg, int w, int h) {
        int sw = Runtime.SCREEN_W;
        int sh = Runtime.SCREEN_H;
        if (filter == Filter.PIXEL) {
            int k = Math.min(w / sw, h / sh);
            if (k >= 1) {
                return centered(sw * k, sh * k, w, h);
            }
        }
        double s = Math.min((double) w / sw, (double) h / sh);
        Rectangle r = centered((int) Math.round(sw * s), (int) Math.round(sh * s), w, h);
        if (bg == Background.FRAME) {
            int t = bezel(r);
            if (r.height + 2 * t > h) {
                double k = (double) (h - 2 * t) / r.height;
                r = centered((int) (r.width * k), h - 2 * t, w, h);
            }
        }
        return r;
    }

    private static Rectangle centered(int dw, int dh, int w, int h) {
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    private static int bezel(Rectangle r) {
        return Math.max(6, r.height / 36);
    }

    /** A simple handheld-style bezel around the picture (drawn under it). */
    static void drawFrame(Graphics2D g, Rectangle r) {
        int t = bezel(r);
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
}
