package drpc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

/**
 * Optional debug helpers, enabled with system properties:
 *  -Ddrpc.dump=dir        saves the presented frame as PNG once per second
 *  -Ddrpc.keys=script     plays phone keys, e.g. "4000:-5,1500:p-5" (delay ms : key code; p = from gamepad,
 *                         -1000 = options menu; optional third field = hold time in ms)
 *  -Ddrpc.burst=start:count  with drpc.dump, saves every presented frame from start ms on
 *  -Ddrpc.autoplay=ms     presses Play in the launcher after ms (with drpc.dump it is saved first)
 */
final class Debug {

    private static final Path DUMP = System.getProperty("drpc.dump") == null ? null : Paths.get(System.getProperty("drpc.dump"));
    private static long lastDump;
    private static int dumpIndex;

    private Debug() {
    }

    static void onPresent(BufferedImage frame) {
        burst(frame);
        if (DUMP == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDump < 1000) {
            return;
        }
        lastDump = now;
        try {
            Files.createDirectories(DUMP);
            ImageIO.write(frame, "png", DUMP.resolve(String.format("frame%03d.png", dumpIndex++)).toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** With drpc.dump set, saves a Swing component (the launcher) as name.png. */
    static void saveComponent(javax.swing.JComponent c, String name) {
        if (DUMP == null) {
            return;
        }
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        try {
            Files.createDirectories(DUMP);
            ImageIO.write(img, "png", DUMP.resolve(name + ".png").toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static long lastWindowDump;
    private static int windowIndex;

    /**
     * With drpc.dump set, saves the whole window (background, menu, legend) once per second.
     * pixels returns the OpenGL framebuffer, bottom row first. Called on the render thread.
     */
    static void onWindow(int w, int h, java.util.function.Supplier<int[]> pixels) {
        if (DUMP == null || w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWindowDump < 1000) {
            return;
        }
        lastWindowDump = now;
        int[] px = pixels.get();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            img.setRGB(0, h - 1 - y, w, 1, px, y * w, w);
        }
        try {
            ImageIO.write(img, "png", DUMP.resolve(String.format("win%03d.png", windowIndex++)).toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final String BURST = System.getProperty("drpc.burst"); // "startMs:count"
    private static final long START = System.currentTimeMillis();
    private static int burstIndex;

    /** Saves every presented frame (not one per second) inside a time window, to check motion. */
    private static void burst(BufferedImage frame) {
        if (BURST == null || DUMP == null) {
            return;
        }
        String[] b = BURST.split(":");
        if (System.currentTimeMillis() - START < Long.parseLong(b[0]) || burstIndex >= Integer.parseInt(b[1])) {
            return;
        }
        try {
            ImageIO.write(frame, "png", DUMP.resolve(String.format("burst%03d.png", burstIndex++)).toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void startKeyScript() {
        String script = System.getProperty("drpc.keys");
        if (script == null || script.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                for (String step : script.split(",")) {
                    String[] p = step.trim().split(":");
                    Thread.sleep(Long.parseLong(p[0]));
                    // "p" prefix = the key comes from a gamepad (e.g. "500:p-5")
                    boolean pad = p[1].startsWith("p");
                    int code = Integer.parseInt(pad ? p[1].substring(1) : p[1]);
                    Input.Device dev = pad ? Input.Device.GAMEPAD : Input.Device.KEYBOARD;
                    System.out.println("[debug] key " + code + (pad ? " (pad)" : ""));
                    Runtime.runOnEventThread(() -> Input.press(code, dev));
                    Thread.sleep(p.length > 2 ? Long.parseLong(p[2]) : 120); // optional hold time
                    Runtime.runOnEventThread(() -> Input.release(code));
                }
            } catch (InterruptedException ignored) {
                // stop
            }
        }, "debug-keys");
        t.setDaemon(true);
        t.start();
    }
}
