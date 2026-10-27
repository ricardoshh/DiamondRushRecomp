package javax.microedition.lcdui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recorded frame: every primitive the game drew on the screen during one tick, with translation
 * already applied (absolute coordinates) and the clip/color in effect. Used by the frame
 * interpolator to redraw the frame with sprites at in-between positions. Not part of MIDP.
 */
public final class DrawList {

    public static final int FILL_RECT = 0;
    public static final int DRAW_RECT = 1;
    public static final int LINE = 2;
    public static final int FILL_ROUND_RECT = 3;
    public static final int DRAW_ROUND_RECT = 4;
    public static final int FILL_ARC = 5;
    public static final int DRAW_ARC = 6;
    public static final int TRIANGLE = 7;
    /** drawImage / drawRegion: a = {sx, sy, w, h, transform}, x/y = absolute top-left after anchor. */
    public static final int REGION = 8;
    /** drawRGB: a = {w, h}, x/y = absolute position, rgb = copied pixels. */
    public static final int RGB = 9;

    /** Receives commands while a frame is being recorded. */
    public interface Sink {
        void add(Cmd c);
    }

    public static final class Cmd {
        public int op;
        public int clipX;
        public int clipY;
        public int clipW;
        public int clipH;
        public int color;
        public int x;
        public int y;
        public int[] a;
        /** Image as drawn by the game (identity used for matching between frames). */
        public Image source;
        /** Image to draw on replay (a frozen copy when the source is mutable). */
        public Image image;
        public int[] rgb;
        public boolean alpha;

        /** Set by the interpolator: position of the same sprite in the previous frame. */
        public boolean matched;
        public int prevX;
        public int prevY;
    }

    public final List<Cmd> cmds = new ArrayList<>();

    /** Screen contents before this frame was drawn (the game only redraws what changed). */
    public java.awt.image.BufferedImage before;

    /**
     * Freezes mutable images referenced by this frame (the game keeps drawing into them, e.g. the
     * scrolling tile cache) so the frame can be replayed later exactly as it was.
     */
    public void freezeImages() {
        Map<Image, Image> copies = new IdentityHashMap<>();
        for (Cmd c : cmds) {
            if (c.source != null) {
                if (c.source.isMutable()) {
                    c.image = copies.computeIfAbsent(c.source, Image::frozenCopy);
                } else {
                    c.image = c.source;
                }
            }
        }
    }

    /** Executes one command on g (a Graphics with no translation). */
    public static void execute(Graphics g, Cmd c, int x, int y) {
        g.setClip(c.clipX, c.clipY, c.clipW, c.clipH);
        g.setColor(c.color);
        int[] a = c.a;
        switch (c.op) {
            case FILL_RECT: g.fillRect(x, y, a[0], a[1]); break;
            case DRAW_RECT: g.drawRect(x, y, a[0], a[1]); break;
            case LINE: g.drawLine(x, y, a[0], a[1]); break;
            case FILL_ROUND_RECT: g.fillRoundRect(x, y, a[0], a[1], a[2], a[3]); break;
            case DRAW_ROUND_RECT: g.drawRoundRect(x, y, a[0], a[1], a[2], a[3]); break;
            case FILL_ARC: g.fillArc(x, y, a[0], a[1], a[2], a[3]); break;
            case DRAW_ARC: g.drawArc(x, y, a[0], a[1], a[2], a[3]); break;
            case TRIANGLE: g.fillTriangle(x, y, a[0], a[1], a[2], a[3]); break;
            case REGION: g.drawRegion(c.image, a[0], a[1], a[2], a[3], a[4], x, y, 0); break;
            case RGB: g.drawRGB(c.rgb, 0, a[0], x, y, a[0], a[1], c.alpha); break;
            default: break;
        }
    }
}
