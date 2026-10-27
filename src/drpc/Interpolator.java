package drpc;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import javax.microedition.lcdui.DrawList;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * High frame rate output for a game whose logic and drawing are locked to 20 ticks per second.
 *
 * Every tick, the screen's draw calls are recorded (DrawList). Each sprite of the new frame is
 * matched with the same sprite in the previous frame, and a presenter thread draws in-between
 * frames at the display rate, moving matched sprites from their previous to their current
 * position. Output lags the game by one tick (~50 ms), the usual cost of interpolation.
 *
 * Diamond Rush draws the level background from a wrap-around tile cache (static i.d, size
 * i.cC x i.cD) that the game updates as the camera scrolls. That cache is redrawn here from the
 * camera offset instead, combining the previous and current cache copies so the column that was
 * just recycled never shows.
 */
final class Interpolator implements DrawList.Sink {

    /** Largest movement (px per tick) still treated as motion; anything bigger is a cut. */
    private static final int MAX_STEP = 40;
    private static final int TILE = 24;

    private static final Interpolator INSTANCE = new Interpolator();

    private static volatile boolean enabled;
    private static volatile int targetFps = 60;

    /** Two consecutive recorded ticks; replaced as a whole so the presenter sees a consistent pair. */
    private static final class Pair {
        final DrawList prev;
        final DrawList cur;
        final long time;      // System.nanoTime() when cur was committed
        final long tickNanos; // measured tick length
        final Ring ring;

        Pair(DrawList prev, DrawList cur, long time, long tickNanos, Ring ring) {
            this.prev = prev;
            this.cur = cur;
            this.time = time;
            this.tickNanos = tickNanos;
            this.ring = ring;
        }
    }

    /** Scroll state of the tile cache in both frames. */
    private static final class Ring {
        int w;
        int h;
        Image prevImg;
        Image curImg;
        int curX;   // cache offset (camera mod w/h) in the current frame
        int curY;
        int dx;     // camera movement since the previous frame
        int dy;
    }

    private DrawList recording = new DrawList();
    private BufferedImage screenBefore;
    private volatile Pair pair;
    private long lastCommit;
    private long tickNanos = 50_000_000L;

    private Field ringField;
    private Field ringWField;
    private Field ringHField;
    private boolean ringLookupDone;

    private Interpolator() {
    }

    static DrawList.Sink sink() {
        return INSTANCE;
    }

    static boolean enabled() {
        return enabled;
    }

    static void configure(int fps) {
        targetFps = fps;
        enabled = fps > 20;
        if (!enabled) {
            INSTANCE.pair = null;
        }
    }

    static int targetFps() {
        return targetFps;
    }

    // ---------------------------------------------------------------- game thread

    @Override
    public synchronized void add(DrawList.Cmd c) {
        recording.cmds.add(c);
    }

    /** Called from flushGraphics: the frame drawn since the last call is complete. */
    static void commit(BufferedImage screen) {
        INSTANCE.doCommit(screen);
    }

    private void doCommit(BufferedImage screen) {
        DrawList cur;
        synchronized (this) {
            cur = recording;
            recording = new DrawList();
        }
        cur.before = screenBefore;
        screenBefore = copy(screen); // starting point of the next frame
        if (cur.cmds.isEmpty()) {
            return;
        }
        cur.freezeImages();
        long now = System.nanoTime();
        if (lastCommit != 0) {
            long dt = now - lastCommit;
            if (dt > 10_000_000L && dt < 250_000_000L) {
                tickNanos = (tickNanos * 3 + dt) / 4;
            }
        }
        lastCommit = now;

        Pair old = pair;
        DrawList prev = old == null ? null : old.cur;
        Ring ring = prev == null ? null : ring(prev, cur);
        if (prev != null) {
            match(prev, cur, ring);
        }
        pair = new Pair(prev, cur, now, tickNanos, ring);
    }

    // ---------------------------------------------------------------- matching

    private record Key(Image img, int sx, int sy, int w, int h, int t) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.img == img && k.sx == sx && k.sy == sy && k.w == w && k.h == h && k.t == t;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(img), sx, sy, w, h, t);
        }
    }

    private static Key key(DrawList.Cmd c) {
        int[] a = c.a;
        return new Key(c.source, a[0], a[1], a[2], a[3], a[4]);
    }

    /**
     * Pairs each sprite of cur with the closest identical sprite of prev. A sprite either stays
     * put on screen (HUD, player followed by the camera) or moves with the world (scrolling), so
     * the distance is measured both ways.
     */
    private void match(DrawList prev, DrawList cur, Ring ring) {
        Image ringImg = ringImage();
        int camX = ring == null ? 0 : ring.dx;
        int camY = ring == null ? 0 : ring.dy;
        Map<Key, List<DrawList.Cmd>> pool = new HashMap<>();
        for (DrawList.Cmd p : prev.cmds) {
            if (p.op == DrawList.REGION && p.source != ringImg) {
                pool.computeIfAbsent(key(p), k -> new ArrayList<>()).add(p);
            }
        }
        for (DrawList.Cmd c : cur.cmds) {
            if (c.op != DrawList.REGION || c.source == ringImg) {
                continue;
            }
            List<DrawList.Cmd> candidates = pool.get(key(c));
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                DrawList.Cmd p = candidates.get(i);
                int still = Math.abs(p.x - c.x) + Math.abs(p.y - c.y);
                int world = Math.abs(p.x - camX - c.x) + Math.abs(p.y - camY - c.y);
                int d = Math.min(still, world);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            DrawList.Cmd p = candidates.get(best);
            if (Math.abs(p.x - c.x) <= MAX_STEP + Math.abs(camX) && Math.abs(p.y - c.y) <= MAX_STEP + Math.abs(camY)
                    && bestDist <= MAX_STEP) {
                c.matched = true;
                c.prevX = p.x;
                c.prevY = p.y;
                candidates.remove(best);
            }
        }
    }

    // ---------------------------------------------------------------- tile cache

    private Image ringImage() {
        if (!ringLookupDone) {
            ringLookupDone = true;
            try {
                Class<?> game = Class.forName("i", false, Runtime.gameClassLoader());
                ringField = game.getDeclaredField("d");
                ringWField = game.getDeclaredField("cC");
                ringHField = game.getDeclaredField("cD");
                ringField.setAccessible(true);
                ringWField.setAccessible(true);
                ringHField.setAccessible(true);
                if (ringField.getType() != Image.class) {
                    ringField = null;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("[drpc] interpolation: tile cache not found (" + e + ")");
                ringField = null;
            }
        }
        if (ringField == null) {
            return null;
        }
        try {
            return (Image) ringField.get(null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Camera offset from the first cache blit: the game draws it at (clip origin - offset). */
    private Ring ring(DrawList prev, DrawList cur) {
        Image img = ringImage();
        if (img == null) {
            return null;
        }
        DrawList.Cmd p = firstBlit(prev, img);
        DrawList.Cmd c = firstBlit(cur, img);
        if (p == null || c == null) {
            return null;
        }
        Ring r = new Ring();
        try {
            r.w = ringWField.getInt(null);
            r.h = ringHField.getInt(null);
        } catch (IllegalAccessException e) {
            return null;
        }
        if (r.w <= 0 || r.h <= 0) {
            return null;
        }
        int px = Math.floorMod(p.clipX - p.x, r.w);
        int py = Math.floorMod(p.clipY - p.y, r.h);
        r.curX = Math.floorMod(c.clipX - c.x, r.w);
        r.curY = Math.floorMod(c.clipY - c.y, r.h);
        r.dx = wrap(r.curX - px, r.w);
        r.dy = wrap(r.curY - py, r.h);
        if (Math.abs(r.dx) > MAX_STEP || Math.abs(r.dy) > MAX_STEP) {
            return null; // camera cut (level start, teleport): don't interpolate the background
        }
        r.prevImg = p.image;
        r.curImg = c.image;
        return r;
    }

    private static DrawList.Cmd firstBlit(DrawList list, Image ringImg) {
        for (DrawList.Cmd c : list.cmds) {
            if (c.op == DrawList.REGION && c.source == ringImg) {
                return c;
            }
        }
        return null;
    }

    private static int wrap(int d, int m) {
        d = Math.floorMod(d, m);
        return d > m / 2 ? d - m : d;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        c.setData(src.getRaster());
        return c;
    }

    // ---------------------------------------------------------------- presenter thread

    static void startPresenter() {
        Thread t = new Thread(INSTANCE::presentLoop, "presenter");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private void presentLoop() {
        BufferedImage frame = new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
        long next = System.nanoTime();
        boolean stats = Boolean.getBoolean("drpc.stats");
        long statStart = next;
        int frames = 0;
        long renderNanos = 0;
        long presentNanos = 0;
        long worstGap = 0;
        long lastFrame = next;
        while (true) {
            long period = 1_000_000_000L / Math.max(1, targetFps);
            next += period;
            long now = System.nanoTime();
            if (next < now - period) {
                next = now; // fell behind (window dragged, GC): don't try to catch up
            }
            Pair p = pair;
            if (enabled && p != null) {
                long t0 = System.nanoTime();
                render(p, frame, now);
                long tr = System.nanoTime();
                Runtime.present(frame);
                if (stats) {
                    long t1 = System.nanoTime();
                    renderNanos += tr - t0;
                    presentNanos += t1 - tr;
                    worstGap = Math.max(worstGap, t1 - lastFrame);
                    lastFrame = t1;
                    if (++frames % 120 == 0) {
                        System.out.printf("[stats] %.1f fps, replay %.2f ms, present %.2f ms, worst gap %.1f ms, tick %.1f ms%n",
                                frames * 1e9 / (t1 - statStart), renderNanos / 1e6 / frames, presentNanos / 1e6 / frames, worstGap / 1e6, tickNanos / 1e6);
                        frames = 0;
                        renderNanos = 0;
                        presentNanos = 0;
                        worstGap = 0;
                        statStart = t1;
                    }
                }
            }
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
        }
    }

    private void render(Pair p, BufferedImage frame, long now) {
        double alpha = p.prev == null ? 1 : Math.min(1.0, (now - p.time) / (double) p.tickNanos);
        if (p.cur.before != null) {
            frame.setData(p.cur.before.getRaster());
        }
        Graphics g = Graphics.forImage(frame);
        Image ringImg = ringImage();
        boolean ringDrawn = false;
        for (DrawList.Cmd c : p.cur.cmds) {
            if (p.ring != null && c.source == ringImg && c.op == DrawList.REGION) {
                if (!ringDrawn) {
                    drawRing(g, p.ring, c, alpha);
                    ringDrawn = true;
                }
                continue;
            }
            int x = c.x;
            int y = c.y;
            if (c.matched && alpha < 1) {
                x = (int) Math.round(c.prevX + (c.x - c.prevX) * alpha);
                y = (int) Math.round(c.prevY + (c.y - c.prevY) * alpha);
            }
            DrawList.execute(g, c, x, y);
        }
    }

    /**
     * Draws the scrolled tile cache for an in-between camera position. The previous copy fills the
     * view, then the current copy is drawn over the part it actually holds (the column/row the
     * game recycled while scrolling is only valid in the previous copy).
     */
    private static void drawRing(Graphics g, Ring r, DrawList.Cmd first, double alpha) {
        double back = 1 - alpha;
        int mx = (int) Math.round(r.curX - r.dx * back);
        int my = (int) Math.round(r.curY - r.dy * back);
        int vx = first.clipX;
        int vy = first.clipY;
        int vw = first.clipW;
        int vh = first.clipH;

        g.setClip(vx, vy, vw, vh);
        tile(g, r.prevImg, r, vx, vy, mx, my);

        // Offset of the view from the cache's first valid tile, in the current frame.
        int offX = (int) Math.round(Math.floorMod(r.curX, TILE) - r.dx * back);
        int offY = (int) Math.round(Math.floorMod(r.curY, TILE) - r.dy * back);
        int x0 = Math.max(vx, vx - offX);
        int y0 = Math.max(vy, vy - offY);
        int x1 = Math.min(vx + vw, vx - offX + r.w);
        int y1 = Math.min(vy + vh, vy - offY + r.h);
        if (x1 > x0 && y1 > y0) {
            g.setClip(x0, y0, x1 - x0, y1 - y0);
            tile(g, r.curImg, r, vx, vy, mx, my);
        }
    }

    private static void tile(Graphics g, Image img, Ring r, int vx, int vy, int mx, int my) {
        int ox = vx - Math.floorMod(mx, r.w);
        int oy = vy - Math.floorMod(my, r.h);
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                g.drawImage(img, ox + i * r.w, oy + j * r.h, 0);
            }
        }
    }
}
