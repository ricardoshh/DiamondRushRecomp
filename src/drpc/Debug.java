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
 *                         -1000 = options menu)
 */
final class Debug {

    private static final Path DUMP = System.getProperty("drpc.dump") == null ? null : Paths.get(System.getProperty("drpc.dump"));
    private static long lastDump;
    private static int dumpIndex;

    private Debug() {
    }

    static void onPresent(BufferedImage frame) {
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

    /** With drpc.dump set, also saves the whole window (menu, legend, background) once per second. */
    static void startWindowDump(javax.swing.JComponent window) {
        if (DUMP == null) {
            return;
        }
        int[] n = {0};
        new javax.swing.Timer(1000, e -> {
            BufferedImage img = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            window.paint(g);
            g.dispose();
            try {
                ImageIO.write(img, "png", DUMP.resolve(String.format("win%03d.png", n[0]++)).toFile());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }).start();
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
                    Thread.sleep(120);
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
