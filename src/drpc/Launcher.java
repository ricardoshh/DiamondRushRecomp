package drpc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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

    public static void main(String[] args) throws Exception {
        Path jar = args.length > 0 ? Paths.get(args[0]) : findGameJar();
        if (jar == null) {
            jar = askForJar();
            if (jar == null) {
                return;
            }
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
        Path saves = Paths.get(System.getProperty("drpc.saves", "saves")).toAbsolutePath();

        Screen[] holder = new Screen[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new Screen(title));
        Screen screen = holder[0];
        Runtime.init(loader, props, screen, saves);

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
        MIDletBridge.start(midlet);
        Debug.startKeyScript();
    }

    // ---------------------------------------------------------------- jar discovery

    /** Looks for a MIDlet jar next to the launcher / in the working directory. */
    private static Path findGameJar() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        try (var files = Files.list(dir)) {
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
        SwingUtilities.invokeAndWait(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Selecione o .jar original do Diamond Rush");
            fc.setFileFilter(new FileNameExtensionFilter("Jogos J2ME (*.jar)", "jar"));
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                result[0] = f.toPath();
            }
        });
        return result[0];
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
