package drpc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

/** Draws button prompts (keyboard keycaps, Xbox and PlayStation buttons) with plain Java2D shapes. */
final class Glyphs {

    enum Style { KEYBOARD, XBOX, PLAYSTATION }

    private static final Color PAD_DARK = new Color(0x2B2D31);
    private static final Color PAD_EDGE = new Color(0x6B6F78);
    private static final Color KEY_FACE = new Color(0xEDEDED);
    private static final Color KEY_SHADOW = new Color(0x9A9A9A);

    private Glyphs() {
    }

    /** Picks the pad style from the device name reported by GLFW. */
    static Style styleForPad(String name) {
        String n = name.toLowerCase();
        if (n.contains("ps3") || n.contains("ps4") || n.contains("ps5") || n.contains("dualshock")
                || n.contains("dualsense") || n.contains("playstation") || n.contains("sony")) {
            return Style.PLAYSTATION;
        }
        return Style.XBOX;
    }

    /**
     * Draws one prompt centered vertically on cy, starting at x. Returns its width.
     * For KEYBOARD, id is the key label ("Enter", "Esc", "arrows"...). For pads it is a
     * button id: a b x y lb rb lt rt start back guide l3 r3 dpad.
     */
    static int draw(Graphics2D g, Style style, String id, int x, int cy, int size) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (style == Style.KEYBOARD) {
            return id.equals("arrows") ? arrowKeys(g, x, cy, size) : keycap(g, id, x, cy, size);
        }
        boolean ps = style == Style.PLAYSTATION;
        switch (id) {
            case "a": case "b": case "x": case "y":
                return ps ? psFace(g, id, x, cy, size) : xboxFace(g, id, x, cy, size);
            case "lb": return pill(g, ps ? "L1" : "LB", x, cy, size);
            case "rb": return pill(g, ps ? "R1" : "RB", x, cy, size);
            case "lt": return pill(g, ps ? "L2" : "LT", x, cy, size);
            case "rt": return pill(g, ps ? "R2" : "RT", x, cy, size);
            case "l3": return pill(g, ps ? "L3" : "LS", x, cy, size);
            case "r3": return pill(g, ps ? "R3" : "RS", x, cy, size);
            case "start": return ps ? pill(g, "OPTIONS", x, cy, size) : menuButton(g, x, cy, size);
            case "back": return ps ? pill(g, "SHARE", x, cy, size) : viewButton(g, x, cy, size);
            case "guide": return pill(g, ps ? "PS" : "GUIDE", x, cy, size);
            case "dpad": return dpad(g, x, cy, size);
            default: return pill(g, id.toUpperCase(), x, cy, size);
        }
    }

    // ---------------------------------------------------------------- pad buttons

    private static int xboxFace(Graphics2D g, String id, int x, int cy, int size) {
        Color c;
        switch (id) {
            case "a": c = new Color(0x3BA53B); break;
            case "b": c = new Color(0xD8363E); break;
            case "x": c = new Color(0x2D7BD6); break;
            default: c = new Color(0xE3B520); break;
        }
        int y = cy - size / 2;
        g.setColor(PAD_DARK);
        g.fillOval(x, y, size, size);
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
        g.drawOval(x + 1, y + 1, size - 2, size - 2);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.62)));
        centerText(g, id.toUpperCase(), x + size / 2, cy, c);
        return size;
    }

    private static int psFace(Graphics2D g, String id, int x, int cy, int size) {
        int y = cy - size / 2;
        g.setColor(PAD_DARK);
        g.fillOval(x, y, size, size);
        float stroke = Math.max(1.5f, size / 10f);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int m = size * 3 / 10;
        int x0 = x + m;
        int y0 = y + m;
        int x1 = x + size - m;
        int y1 = y + size - m;
        switch (id) {
            case "a": // cross
                g.setColor(new Color(0x7EA7E6));
                g.drawLine(x0, y0, x1, y1);
                g.drawLine(x0, y1, x1, y0);
                break;
            case "b": // circle
                g.setColor(new Color(0xE0525A));
                g.drawOval(x0, y0, x1 - x0, y1 - y0);
                break;
            case "x": // square
                g.setColor(new Color(0xD27BC0));
                g.drawRect(x0, y0, x1 - x0, y1 - y0);
                break;
            default: // triangle
                g.setColor(new Color(0x3FBF8F));
                g.drawPolygon(new int[] {x + size / 2, x1, x0}, new int[] {y0 - 1, y1, y1}, 3);
                break;
        }
        return size;
    }

    private static int pill(Graphics2D g, String label, int x, int cy, int size) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.45)));
        int w = Math.max(size * 3 / 2, g.getFontMetrics().stringWidth(label) + size * 2 / 3);
        int h = size * 3 / 4;
        g.setColor(PAD_DARK);
        g.fillRoundRect(x, cy - h / 2, w, h, h, h);
        g.setColor(PAD_EDGE);
        g.setStroke(new BasicStroke(Math.max(1f, size / 18f)));
        g.drawRoundRect(x, cy - h / 2, w, h, h, h);
        centerText(g, label, x + w / 2, cy, Color.WHITE);
        return w;
    }

    private static int menuButton(Graphics2D g, int x, int cy, int size) {
        int d = size * 3 / 4;
        roundBase(g, x, cy, d);
        g.setColor(Color.WHITE);
        int lx0 = x + d / 4;
        int lx1 = x + d - d / 4;
        int gap = d / 6;
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 16f)));
        for (int i = -1; i <= 1; i++) {
            g.drawLine(lx0, cy + i * gap, lx1, cy + i * gap);
        }
        return d;
    }

    private static int viewButton(Graphics2D g, int x, int cy, int size) {
        int d = size * 3 / 4;
        roundBase(g, x, cy, d);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 16f)));
        int s = d / 3;
        g.drawRect(x + d / 4, cy - s / 2 - s / 4, s, s);
        g.drawRect(x + d / 4 + s / 2, cy - s / 2 + s / 4, s, s);
        return d;
    }

    private static void roundBase(Graphics2D g, int x, int cy, int d) {
        g.setColor(PAD_DARK);
        g.fillOval(x, cy - d / 2, d, d);
        g.setColor(PAD_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(x, cy - d / 2, d, d);
    }

    private static int dpad(Graphics2D g, int x, int cy, int size) {
        int t = size / 3;
        int cx = x + size / 2;
        g.setColor(PAD_DARK);
        g.fillRoundRect(cx - t / 2, cy - size / 2, t, size, t / 2, t / 2);
        g.fillRoundRect(x, cy - t / 2, size, t, t / 2, t / 2);
        g.setColor(Color.WHITE);
        int a = Math.max(2, t / 3);
        triangle(g, cx, cy - size / 2 + t / 2, a, 0);
        triangle(g, cx, cy + size / 2 - t / 2, a, 2);
        triangle(g, x + t / 2, cy, a, 3);
        triangle(g, x + size - t / 2, cy, a, 1);
        return size;
    }

    // ---------------------------------------------------------------- keyboard

    private static int keycap(Graphics2D g, String label, int x, int cy, int size) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.45)));
        int w = Math.max(size, g.getFontMetrics().stringWidth(label) + size / 2);
        int h = size;
        int y = cy - h / 2;
        int r = size / 4;
        g.setColor(KEY_SHADOW);
        g.fillRoundRect(x, y + size / 10, w, h - size / 10, r, r);
        g.setColor(KEY_FACE);
        g.fillRoundRect(x, y, w, h - size / 10, r, r);
        centerText(g, label, x + w / 2, cy - size / 20, new Color(0x222222));
        return w;
    }

    private static int arrowKeys(Graphics2D g, int x, int cy, int size) {
        int k = size * 3 / 5;
        int gap = Math.max(1, size / 14);
        int w = k * 3 + gap * 2;
        int top = cy - k - gap / 2;
        smallKey(g, x + k + gap, top, k, 0);
        smallKey(g, x, top + k + gap, k, 3);
        smallKey(g, x + k + gap, top + k + gap, k, 2);
        smallKey(g, x + 2 * (k + gap), top + k + gap, k, 1);
        return w;
    }

    private static void smallKey(Graphics2D g, int x, int y, int k, int dir) {
        int r = k / 4;
        g.setColor(KEY_SHADOW);
        g.fillRoundRect(x, y + k / 10, k, k - k / 10, r, r);
        g.setColor(KEY_FACE);
        g.fillRoundRect(x, y, k, k - k / 10, r, r);
        g.setColor(new Color(0x222222));
        triangle(g, x + k / 2, y + k / 2 - k / 20, Math.max(2, k / 4), dir);
    }

    // ---------------------------------------------------------------- helpers

    /** Small filled triangle pointing up (0), right (1), down (2) or left (3). */
    private static void triangle(Graphics2D g, int cx, int cy, int a, int dir) {
        Polygon p = new Polygon();
        switch (dir) {
            case 0: p.addPoint(cx, cy - a); p.addPoint(cx + a, cy + a / 2); p.addPoint(cx - a, cy + a / 2); break;
            case 1: p.addPoint(cx + a, cy); p.addPoint(cx - a / 2, cy - a); p.addPoint(cx - a / 2, cy + a); break;
            case 2: p.addPoint(cx, cy + a); p.addPoint(cx + a, cy - a / 2); p.addPoint(cx - a, cy - a / 2); break;
            default: p.addPoint(cx - a, cy); p.addPoint(cx + a / 2, cy - a); p.addPoint(cx + a / 2, cy + a); break;
        }
        g.fillPolygon(p);
    }

    private static void centerText(Graphics2D g, String s, int cx, int cy, Color c) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }
}
