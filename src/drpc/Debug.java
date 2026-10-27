package drpc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;
import javax.microedition.lcdui.Display;

/**
 * Optional debug helpers, enabled with system properties:
 *  -Ddrpc.dump=dir        saves the presented frame as PNG once per second
 *  -Ddrpc.keys=script     plays phone keys, e.g. "4000:-5,1500:-5,500:-1" (delay ms : key code)
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
                    int code = Integer.parseInt(p[1]);
                    System.out.println("[debug] key " + code);
                    Runtime.runOnEventThread(() -> Display.keyEvent(code, 0));
                    Thread.sleep(120);
                    Runtime.runOnEventThread(() -> Display.keyEvent(code, 1));
                }
            } catch (InterruptedException ignored) {
                // stop
            }
        }, "debug-keys");
        t.setDaemon(true);
        t.start();
    }
}
