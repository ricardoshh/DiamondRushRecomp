package drpc;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import javax.swing.SwingUtilities;

/** Global state shared between the launcher, the window and the J2ME API layer. */
public final class Runtime {

    public static final int SCREEN_W = 240;
    public static final int SCREEN_H = 320;

    private static ClassLoader gameLoader;
    private static Map<String, String> appProperties;
    private static Screen screen;
    private static Path saveDir;
    private static volatile int masterVolume = 100;
    private static final BufferedImage scratch =
            new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);

    private Runtime() {
    }

    static void init(ClassLoader loader, Map<String, String> props, Screen s, Path saves) {
        gameLoader = loader;
        appProperties = props;
        screen = s;
        saveDir = saves;
    }

    public static String getAppProperty(String key) {
        return appProperties.get(key);
    }

    /** Loads a resource from the game jar; MIDP paths are absolute ("/w0.bin"). */
    public static InputStream openResource(String name) {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        return gameLoader.getResourceAsStream(name);
    }

    /** Replacement for Class.getResourceAsStream in game bytecode (see GameClassLoader). */
    public static InputStream classResource(Class<?> c, String name) {
        // All game classes live in the default package, so relative and absolute names coincide.
        return openResource(name);
    }

    public static Path saveDir() {
        return saveDir;
    }

    public static void present(BufferedImage frame) {
        screen.present(frame);
        Debug.onPresent(frame);
    }

    public static BufferedImage frameScratch() {
        return scratch;
    }

    public static void runOnEventThread(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    public static boolean vibrate(int millis) {
        return true;
    }

    public static int masterVolume() {
        return masterVolume;
    }

    public static void setMasterVolume(int v) {
        masterVolume = Math.max(0, Math.min(100, v));
        MidiPlayer.refreshVolumeAll();
    }

    public static void exit() {
        System.exit(0);
    }
}
