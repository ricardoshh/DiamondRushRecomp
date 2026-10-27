package javax.microedition.lcdui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import javax.microedition.lcdui.game.Sprite;

/**
 * MIDP Graphics on top of Java2D. Translation and clip are tracked here (MIDP semantics)
 * and every primitive is issued to the Graphics2D in absolute target coordinates.
 */
public class Graphics {

    public static final int HCENTER = 1;
    public static final int VCENTER = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int TOP = 16;
    public static final int BOTTOM = 32;
    public static final int BASELINE = 64;

    public static final int SOLID = 0;
    public static final int DOTTED = 1;

    private final Graphics2D g;
    private final int targetW;
    private final int targetH;

    private int tx;
    private int ty;
    // clip in absolute target coordinates
    private int clipX;
    private int clipY;
    private int clipW;
    private int clipH;
    private int color;
    private int strokeStyle = SOLID;
    private DrawList.Sink sink; // non-null while the frame interpolator records this screen

    Graphics(Graphics2D g, int w, int h) {
        this.g = g;
        this.targetW = w;
        this.targetH = h;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setComposite(AlphaComposite.SrcOver);
        clipX = 0;
        clipY = 0;
        clipW = w;
        clipH = h;
        g.setClip(0, 0, w, h);
        setColor(0);
    }

    /** Creates a Graphics drawing into a BufferedImage (used by Image and GameCanvas). */
    public static Graphics forImage(BufferedImage img) {
        return new Graphics(img.createGraphics(), img.getWidth(), img.getHeight());
    }

    /** Like forImage, also recording every primitive into sink (screen graphics of a GameCanvas). */
    public static Graphics forImage(BufferedImage img, DrawList.Sink sink) {
        Graphics g = forImage(img);
        g.sink = sink;
        return g;
    }

    private DrawList.Cmd rec(int op, int x, int y, int... a) {
        DrawList.Cmd c = new DrawList.Cmd();
        c.op = op;
        c.clipX = clipX;
        c.clipY = clipY;
        c.clipW = clipW;
        c.clipH = clipH;
        c.color = color;
        c.x = x;
        c.y = y;
        c.a = a;
        sink.add(c);
        return c;
    }

    // ---------------------------------------------------------------- state

    public void translate(int x, int y) {
        tx += x;
        ty += y;
    }

    public int getTranslateX() {
        return tx;
    }

    public int getTranslateY() {
        return ty;
    }

    public void setColor(int rgb) {
        color = rgb & 0xFFFFFF;
        g.setColor(new Color(color));
    }

    public void setColor(int r, int gr, int b) {
        setColor(((r & 0xFF) << 16) | ((gr & 0xFF) << 8) | (b & 0xFF));
    }

    public int getColor() {
        return color;
    }

    public int getRedComponent() {
        return (color >> 16) & 0xFF;
    }

    public int getGreenComponent() {
        return (color >> 8) & 0xFF;
    }

    public int getBlueComponent() {
        return color & 0xFF;
    }

    public void setGrayScale(int v) {
        setColor(v, v, v);
    }

    public int getGrayScale() {
        return (getRedComponent() + getGreenComponent() + getBlueComponent()) / 3;
    }

    public void setStrokeStyle(int style) {
        strokeStyle = style;
    }

    public int getStrokeStyle() {
        return strokeStyle;
    }

    public int getClipX() {
        return clipX - tx;
    }

    public int getClipY() {
        return clipY - ty;
    }

    public int getClipWidth() {
        return clipW;
    }

    public int getClipHeight() {
        return clipH;
    }

    public void setClip(int x, int y, int w, int h) {
        applyClip(x + tx, y + ty, w, h, 0, 0, targetW, targetH);
    }

    public void clipRect(int x, int y, int w, int h) {
        applyClip(x + tx, y + ty, w, h, clipX, clipY, clipW, clipH);
    }

    private void applyClip(int x, int y, int w, int h, int bx, int by, int bw, int bh) {
        int x1 = Math.max(x, bx);
        int y1 = Math.max(y, by);
        int x2 = Math.min(x + w, bx + bw);
        int y2 = Math.min(y + h, by + bh);
        clipX = x1;
        clipY = y1;
        clipW = Math.max(0, x2 - x1);
        clipH = Math.max(0, y2 - y1);
        g.setClip(clipX, clipY, clipW, clipH);
    }

    // ---------------------------------------------------------------- primitives

    public void drawLine(int x1, int y1, int x2, int y2) {
        if (sink != null) {
            rec(DrawList.LINE, x1 + tx, y1 + ty, x2 + tx, y2 + ty);
        }
        g.drawLine(x1 + tx, y1 + ty, x2 + tx, y2 + ty);
    }

    public void fillRect(int x, int y, int w, int h) {
        if (sink != null) {
            rec(DrawList.FILL_RECT, x + tx, y + ty, w, h);
        }
        if (w > 0 && h > 0) {
            g.fillRect(x + tx, y + ty, w, h);
        }
    }

    public void drawRect(int x, int y, int w, int h) {
        if (sink != null) {
            rec(DrawList.DRAW_RECT, x + tx, y + ty, w, h);
        }
        if (w >= 0 && h >= 0) {
            g.drawRect(x + tx, y + ty, w, h);
        }
    }

    public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) {
        if (sink != null) {
            rec(DrawList.DRAW_ROUND_RECT, x + tx, y + ty, w, h, aw, ah);
        }
        g.drawRoundRect(x + tx, y + ty, w, h, aw, ah);
    }

    public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) {
        if (sink != null) {
            rec(DrawList.FILL_ROUND_RECT, x + tx, y + ty, w, h, aw, ah);
        }
        g.fillRoundRect(x + tx, y + ty, w, h, aw, ah);
    }

    public void drawArc(int x, int y, int w, int h, int start, int arc) {
        if (sink != null) {
            rec(DrawList.DRAW_ARC, x + tx, y + ty, w, h, start, arc);
        }
        g.drawArc(x + tx, y + ty, w, h, start, arc);
    }

    public void fillArc(int x, int y, int w, int h, int start, int arc) {
        if (sink != null) {
            rec(DrawList.FILL_ARC, x + tx, y + ty, w, h, start, arc);
        }
        g.fillArc(x + tx, y + ty, w, h, start, arc);
    }

    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3) {
        if (sink != null) {
            rec(DrawList.TRIANGLE, x1 + tx, y1 + ty, x2 + tx, y2 + ty, x3 + tx, y3 + ty);
        }
        g.fillPolygon(new int[] {x1 + tx, x2 + tx, x3 + tx}, new int[] {y1 + ty, y2 + ty, y3 + ty}, 3);
    }

    public void drawRGB(int[] rgb, int offset, int scanlength, int x, int y, int w, int h, boolean alpha) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (sink != null) {
            int[] copy = new int[w * h];
            for (int row = 0; row < h; row++) {
                System.arraycopy(rgb, offset + row * scanlength, copy, row * w, w);
            }
            DrawList.Cmd c = rec(DrawList.RGB, x + tx, y + ty, w, h);
            c.rgb = copy;
            c.alpha = alpha;
        }
        BufferedImage tmp = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        tmp.setRGB(0, 0, w, h, rgb, offset, scanlength);
        g.drawImage(tmp, x + tx, y + ty, null);
    }

    // ---------------------------------------------------------------- images

    public void drawImage(Image img, int x, int y, int anchor) {
        BufferedImage src = img.image;
        int w = src.getWidth();
        int h = src.getHeight();
        x = anchorX(x, w, anchor);
        y = anchorY(y, h, anchor);
        if (sink != null) {
            rec(DrawList.REGION, x + tx, y + ty, 0, 0, w, h, Sprite.TRANS_NONE).source = img;
        }
        g.drawImage(src, x + tx, y + ty, null);
    }

    public void drawRegion(Image img, int sx, int sy, int w, int h, int transform, int dx, int dy, int anchor) {
        if (w <= 0 || h <= 0) {
            return;
        }
        BufferedImage src = img.image;
        boolean swap = transform >= Sprite.TRANS_MIRROR_ROT270; // 4..7 swap width/height
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        dx = anchorX(dx, dw, anchor) + tx;
        dy = anchorY(dy, dh, anchor) + ty;
        if (sink != null) {
            rec(DrawList.REGION, dx, dy, sx, sy, w, h, transform).source = img;
        }

        if (transform == Sprite.TRANS_NONE) {
            g.drawImage(src, dx, dy, dx + w, dy + h, sx, sy, sx + w, sy + h, null);
            return;
        }

        // Map region-local (x, y) -> destination-local coordinates.
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12
        AffineTransform t;
        switch (transform) {
            case Sprite.TRANS_ROT90:          t = new AffineTransform(0, 1, -1, 0, h, 0); break;
            case Sprite.TRANS_ROT180:         t = new AffineTransform(-1, 0, 0, -1, w, h); break;
            case Sprite.TRANS_ROT270:         t = new AffineTransform(0, -1, 1, 0, 0, w); break;
            case Sprite.TRANS_MIRROR:         t = new AffineTransform(-1, 0, 0, 1, w, 0); break;
            case Sprite.TRANS_MIRROR_ROT90:   t = new AffineTransform(0, -1, -1, 0, h, w); break;
            case Sprite.TRANS_MIRROR_ROT180:  t = new AffineTransform(1, 0, 0, -1, 0, h); break;
            case Sprite.TRANS_MIRROR_ROT270:  t = new AffineTransform(0, 1, 1, 0, 0, 0); break;
            default: throw new IllegalArgumentException("bad transform " + transform);
        }
        AffineTransform full = AffineTransform.getTranslateInstance(dx, dy);
        full.concatenate(t);
        g.drawImage(src.getSubimage(sx, sy, w, h), full, null);
    }

    public void copyArea(int sx, int sy, int w, int h, int dx, int dy, int anchor) {
        dx = anchorX(dx, w, anchor);
        dy = anchorY(dy, h, anchor);
        g.copyArea(sx + tx, sy + ty, w, h, dx - sx, dy - sy);
    }

    private static int anchorX(int x, int w, int anchor) {
        if ((anchor & HCENTER) != 0) {
            return x - w / 2;
        }
        if ((anchor & RIGHT) != 0) {
            return x - w;
        }
        return x;
    }

    private static int anchorY(int y, int h, int anchor) {
        if ((anchor & VCENTER) != 0) {
            return y - h / 2;
        }
        if ((anchor & BOTTOM) != 0) {
            return y - h;
        }
        return y;
    }
}
