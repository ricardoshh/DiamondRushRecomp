package drpc;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.imageio.ImageIO;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletBridge;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Entry point. Loads the user's original Diamond Rush .jar and runs its unmodified bytecode
 * on top of our javax.microedition implementation. No game code or assets ship with this project.
 */
public final class Launcher {

    private Launcher() {
    }

    /**
     * Folder for settings, saves and the game jar: next to the .exe when packaged with jpackage
     * (a portable install), otherwise the working directory.
     */
    static final Path HOME = System.getProperty("jpackage.app-path") != null
            ? Paths.get(System.getProperty("jpackage.app-path")).toAbsolutePath().getParent()
            : Paths.get("").toAbsolutePath();

    private static int appArgCount;

    public static void main(String[] args) throws Exception {
        appArgCount = args.length;
        // We scale the 240x320 image ourselves; keep Windows display scaling from blurring it twice.
        System.setProperty("sun.java2d.uiScale", "1");
        if (System.getProperty("jpackage.app-path") != null) {
            // Packaged builds have no console: keep a log next to the exe for bug reports.
            java.io.PrintStream log = new java.io.PrintStream(
                    Files.newOutputStream(HOME.resolve("log.txt")), true, "UTF-8");
            System.setOut(log);
            System.setErr(log);
        }
        Settings settings = Settings.load(HOME.resolve(System.getProperty("drpc.settings", "settings.properties")));

        Path jar = args.length > 0 ? Paths.get(args[0]) : findGameJar(settings);
        if (jar == null) {
            jar = askForJar();
            if (jar == null) {
                return;
            }
            settings.set("game.jar", jar.toAbsolutePath().toString().replace('\\', '/'));
        }

        Map<String, String> props = readManifest(jar);
        String midletEntry = props.get("MIDlet-1");
        if (midletEntry == null) {
            fail("O arquivo nao parece ser um jogo J2ME (sem MIDlet-1 no manifest):\n" + jar);
            return;
        }
        String mainClass = midletEntry.split(",")[2].trim();
        String title = props.getOrDefault("MIDlet-Name", "J2ME");
        System.out.println("[drpc] " + title + " " + props.get("MIDlet-Version") + " -> " + mainClass);

        GameClassLoader loader = new GameClassLoader(jar.toUri().toURL(), Launcher.class.getClassLoader());
        Path saves = HOME.resolve(System.getProperty("drpc.saves", "saves"));

        Screen[] holder = new Screen[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new Screen(title, settings));
        Screen screen = holder[0];
        screen.setGameJar(jar);
        Runtime.init(loader, props, screen, saves);
        Gamepad.start(settings, screen);
        Interpolator.configure((int) settings.getFloat("video.fps", 60));
        Interpolator.startPresenter();

        MIDlet midlet = (MIDlet) loader.loadClass(mainClass).getDeclaredConstructor().newInstance();
        screen.setOnClose(() -> {
            try {
                MIDletBridge.destroy(midlet);
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.exit(0);
        });

        java.awt.Image icon = loadIcon(props.get("MIDlet-Icon"));
        SwingUtilities.invokeAndWait(() -> screen.show(icon));
        SwingUtilities.invokeLater(() -> Debug.startWindowDump(screen));
        MIDletBridge.start(midlet);
        Debug.startKeyScript();
    }

    // ---------------------------------------------------------------- jar discovery

    /**
     * Finds the game: the jar chosen in settings, else one embedded in a personal build
     * (game.bin next to our own jar, see package.ps1 -GameJar), else any MIDlet jar in the home folder.
     */
    private static Path findGameJar(Settings settings) throws IOException {
        String remembered = settings.get("game.jar", "");
        if (!remembered.isEmpty() && Files.exists(Paths.get(remembered))) {
            return Paths.get(remembered);
        }
        try {
            Path own = Paths.get(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // not named .jar: jpackage would put it on the app classpath and bypass GameClassLoader
            Path embedded = own.resolveSibling("game.bin");
            if (Files.isRegularFile(embedded) && isMidletJar(embedded)) {
                return embedded;
            }
        } catch (Exception ignored) {
            // running from a classes folder, not a jar
        }
        try (var files = Files.list(HOME)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.toString().toLowerCase().endsWith(".jar") && isMidletJar(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean isMidletJar(Path p) {
        try {
            return readManifest(p).containsKey("MIDlet-1");
        } catch (IOException e) {
            return false;
        }
    }

    private static Path askForJar() throws Exception {
        Path[] result = new Path[1];
        SwingUtilities.invokeAndWait(() -> result[0] = chooseJar(null));
        return result[0];
    }

    /** File dialog for the original game jar. Call on the event thread. Returns null if cancelled. */
    static Path chooseJar(java.awt.Component parent) {
        JFileChooser fc = new JFileChooser(HOME.toFile());
        fc.setDialogTitle("Selecione o .jar original do Diamond Rush");
        fc.setFileFilter(new FileNameExtensionFilter("Jogos J2ME (*.jar)", "jar"));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path p = fc.getSelectedFile().toPath();
        if (!isMidletJar(p)) {
            fail("O arquivo nao parece ser um jogo J2ME (sem MIDlet-1 no manifest):\n" + p);
            return null;
        }
        return p;
    }

    /** Starts a fresh copy of this program (same command and JVM options, minus a jar argument) and exits. */
    static void relaunch() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        List<String> cmd = new ArrayList<>();
        cmd.add(info.command().orElseThrow());
        String[] a = info.arguments().orElse(new String[0]);
        cmd.addAll(Arrays.asList(a).subList(0, Math.max(0, a.length - appArgCount)));
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (IOException e) {
            fail("Nao foi possivel reiniciar: " + e);
            return;
        }
        System.exit(0);
    }

    private static Map<String, String> readManifest(Path jar) throws IOException {
        Map<String, String> props = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                for (Map.Entry<Object, Object> e : mf.getMainAttributes().entrySet()) {
                    props.put(((Attributes.Name) e.getKey()).toString(), (String) e.getValue());
                }
            }
        }
        return props;
    }

    private static java.awt.Image loadIcon(String name) {
        if (name == null) {
            return null;
        }
        try (InputStream in = Runtime.openResource(name)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static void fail(String msg) {
        System.err.println(msg);
        JOptionPane.showMessageDialog(null, msg, "DiamondRushRecomp", JOptionPane.ERROR_MESSAGE);
    }
}
