package drpc;

import static org.lwjgl.glfw.GLFW.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Window shown before the game: import the game file, change settings, see connected
 * controllers, then play. Everything is saved to settings.properties, the same file the in-game
 * options menu uses.
 */
final class LauncherWindow {

    private static final String[][] PHONE_KEYS = {
        {"ok", "OK (5)"}, {"softleft", "Soft esquerda (menu)"}, {"softright", "Soft direita (voltar)"},
        {"up", "Cima"}, {"down", "Baixo"}, {"left", "Esquerda"}, {"right", "Direita"},
        {"0", "0"}, {"1", "1"}, {"2", "2"}, {"3", "3"}, {"4", "4"}, {"5", "5"}, {"6", "6"},
        {"7", "7"}, {"8", "8"}, {"9", "9"}, {"star", "*"}, {"pound", "#"}, {"none", "Nada"},
    };
    private static final String[][] PAD_BUTTONS = {
        {"pad.a", "A"}, {"pad.b", "B"}, {"pad.x", "X"}, {"pad.y", "Y"}, {"pad.lb", "LB"}, {"pad.rb", "RB"},
        {"pad.lt", "LT"}, {"pad.rt", "RT"}, {"pad.start", "Start"}, {"pad.back", "Back / Select"},
        {"pad.l3", "Analógico esq. (L3)"}, {"pad.r3", "Analógico dir. (R3)"},
    };

    private final Settings settings;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean play;
    private volatile boolean polling = true;
    private volatile String padStatus = "Procurando controles...";

    private JFrame frame;
    private JLabel gameIcon;
    private JLabel gameName;
    private JLabel gameInfo;
    private JLabel padLabel;
    private JButton playButton;

    private LauncherWindow(Settings settings) {
        this.settings = settings;
    }

    /** Shows the launcher and blocks until the player presses Play (true) or closes it (false). */
    static boolean showAndWait(Settings settings) throws InterruptedException {
        LauncherWindow w = new LauncherWindow(settings);
        Thread pads = new Thread(w::pollPads, "launcher-pads");
        pads.setDaemon(true);
        pads.start();
        SwingUtilities.invokeLater(w::build);
        w.done.await();
        w.polling = false;
        pads.join(2000); // GLFW must be shut down here before the game window initializes it again
        return w.play;
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        FlatDarkLaf.setup();
        frame = new JFrame("DiamondRushRecomp");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                done.countDown();
            }
        });

        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));
        root.add(gamePanel(), BorderLayout.WEST);
        root.add(settingsTabs(), BorderLayout.CENTER);
        root.add(bottomBar(), BorderLayout.SOUTH);
        frame.setContentPane(root);

        refreshGame();
        new Timer(500, e -> padLabel.setText(padStatus)).start();

        frame.setMinimumSize(new Dimension(820, 520));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        playButton.requestFocusInWindow();
        Integer auto = Integer.getInteger("drpc.autoplay");
        if (auto != null) {
            Timer t = new Timer(auto, e -> {
                Debug.saveComponent(root, "launcher");
                playButton.doClick();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    private JComponent gamePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setPreferredSize(new Dimension(260, 0));

        JLabel title = new JLabel("DiamondRushRecomp");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(new Color(0xE8C04A));
        p.add(title);
        JLabel sub = new JLabel("Port nativo do Diamond Rush (J2ME)");
        sub.setForeground(new Color(0x9AA0AA));
        p.add(sub);
        p.add(Box.createVerticalStrut(24));

        gameIcon = new JLabel();
        gameIcon.setPreferredSize(new Dimension(96, 96));
        p.add(gameIcon);
        p.add(Box.createVerticalStrut(8));
        gameName = new JLabel();
        gameName.setFont(gameName.getFont().deriveFont(Font.BOLD, 15f));
        p.add(gameName);
        gameInfo = new JLabel();
        gameInfo.setForeground(new Color(0x9AA0AA));
        p.add(gameInfo);
        p.add(Box.createVerticalStrut(12));

        JButton importButton = new JButton("Importar jogo (.jar)...");
        importButton.addActionListener(e -> importGame());
        p.add(importButton);
        p.add(Box.createVerticalStrut(24));

        JLabel padTitle = new JLabel("Controles");
        padTitle.setFont(padTitle.getFont().deriveFont(Font.BOLD));
        p.add(padTitle);
        padLabel = new JLabel(padStatus);
        padLabel.setForeground(new Color(0x9AA0AA));
        p.add(padLabel);
        p.add(Box.createVerticalGlue());

        JLabel legal = new JLabel("<html>Você precisa do seu próprio .jar do jogo.<br>Nenhum arquivo da Gameloft vem incluído.</html>");
        legal.setForeground(new Color(0x6B6F78));
        legal.setFont(legal.getFont().deriveFont(11f));
        p.add(legal);
        for (java.awt.Component c : p.getComponents()) {
            ((JComponent) c).setAlignmentX(0f);
        }
        return p;
    }

    private JComponent settingsTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Vídeo", videoTab());
        tabs.addTab("Jogo", gameTab());
        tabs.addTab("Controles", controlsTab());
        return tabs;
    }

    private JComponent videoTab() {
        Form f = new Form();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Upscaler.Filter x : Upscaler.Filter.values()) {
            ids.add(x.id);
            labels.add(x.label);
        }
        f.choice("Filtro de imagem", "video.filter", "sharp", ids, labels);

        ids = new ArrayList<>();
        labels = new ArrayList<>();
        for (Upscaler.Background x : Upscaler.Background.values()) {
            ids.add(x.id);
            labels.add(x.label);
        }
        f.choice("Fundo / bordas", "video.background", "blur", ids, labels);

        ids = new ArrayList<>();
        labels = new ArrayList<>();
        for (int fps : Screen.FPS) {
            ids.add(String.valueOf(fps));
            labels.add(fps <= 20 ? "Original (20 fps)" : fps + " fps (suavizado)");
        }
        f.choice("Taxa de quadros", "video.fps", "60", ids, labels);

        f.check("Tela cheia", "video.fullscreen", false);

        ids = new ArrayList<>();
        labels = new ArrayList<>();
        for (int k = 1; k <= Screen.maxWindowScale(); k++) {
            ids.add(String.valueOf(k));
            labels.add(k + "x  (" + Runtime.SCREEN_W * k + " x " + Runtime.SCREEN_H * k + ")");
        }
        String scale = settings.get("video.windowScale", "0");
        if (!ids.contains(scale)) {
            settings.set("video.windowScale", String.valueOf(Screen.maxWindowScale()));
        }
        f.choice("Tamanho da janela", "video.windowScale", String.valueOf(Screen.maxWindowScale()), ids, labels);
        f.note("Atalhos no jogo: F11 tela cheia, F10 troca o filtro, F12 abre as opções.");
        return f.panel;
    }

    private JComponent gameTab() {
        Form f = new Form();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int s : Screen.SPEEDS) {
            ids.add(String.valueOf(s));
            labels.add(s + "%" + (s == 100 ? " (original)" : ""));
        }
        f.choice("Velocidade do jogo", "game.speed", "100", ids, labels);
        f.slider("Volume", "audio.volume", 100);
        f.note("A velocidade muda o jogo inteiro; 100% é a velocidade original do celular.");
        return f.panel;
    }

    private JComponent controlsTab() {
        Form f = new Form();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Screen.GLYPH_PREFS.length; i++) {
            ids.add(Screen.GLYPH_PREFS[i]);
            labels.add(Screen.GLYPH_LABELS[i]);
        }
        f.choice("Ícones de botões", "input.glyphs", "auto", ids, labels);
        f.check("Mostrar controles ao lado do jogo", "input.legend", true);
        f.section("Botões do controle");
        List<String> keyIds = new ArrayList<>();
        List<String> keyLabels = new ArrayList<>();
        for (String[] k : PHONE_KEYS) {
            keyIds.add(k[0]);
            keyLabels.add(k[1]);
        }
        for (String[] b : PAD_BUTTONS) {
            f.choice(b[1], b[0], "none", keyIds, keyLabels);
        }
        f.note("D-pad e analógico esquerdo movem. Guide ou Back+Start abrem as opções no jogo.");
        return new javax.swing.JScrollPane(f.panel);
    }

    private JComponent bottomBar() {
        JPanel bar = new JPanel(new BorderLayout());
        JCheckBox show = new JCheckBox("Mostrar este launcher ao iniciar", settings.getBool("launcher.show", true));
        show.addActionListener(e -> settings.set("launcher.show", String.valueOf(show.isSelected())));
        bar.add(show, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton exit = new JButton("Sair");
        exit.addActionListener(e -> frame.dispose());
        playButton = new JButton("Jogar");
        playButton.putClientProperty("JButton.buttonType", "default");
        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 14f));
        playButton.setPreferredSize(new Dimension(140, 36));
        playButton.addActionListener(e -> {
            play = true;
            frame.dispose();
        });
        frame.getRootPane().setDefaultButton(playButton);
        buttons.add(exit);
        buttons.add(playButton);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    /** Label + control rows for a tab, each control bound to one settings key. */
    private final class Form {
        final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        Form() {
            panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        void choice(String label, String key, String def, List<String> ids, List<String> labels) {
            JComboBox<String> box = new JComboBox<>(labels.toArray(new String[0]));
            int i = ids.indexOf(settings.get(key, def));
            box.setSelectedIndex(Math.max(0, i));
            box.addActionListener(e -> settings.set(key, ids.get(box.getSelectedIndex())));
            add(label, box);
        }

        void check(String label, String key, boolean def) {
            JCheckBox box = new JCheckBox();
            box.setSelected(settings.getBool(key, def));
            box.addActionListener(e -> settings.set(key, String.valueOf(box.isSelected())));
            add(label, box);
        }

        void slider(String label, String key, int def) {
            JSlider s = new JSlider(0, 100, (int) settings.getFloat(key, def));
            s.setMajorTickSpacing(25);
            s.setPaintTicks(true);
            JLabel value = new JLabel(s.getValue() + "%");
            s.addChangeListener(e -> {
                value.setText(s.getValue() + "%");
                if (!s.getValueIsAdjusting()) {
                    settings.set(key, String.valueOf(s.getValue() / 10 * 10));
                }
            });
            JPanel p = new JPanel(new BorderLayout(8, 0));
            p.add(s, BorderLayout.CENTER);
            p.add(value, BorderLayout.EAST);
            add(label, p);
        }

        void section(String text) {
            JLabel l = new JLabel(text);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.insets = new Insets(16, 0, 6, 0);
            panel.add(l, c);
            row++;
        }

        void note(String text) {
            JLabel l = new JLabel("<html>" + text + "</html>");
            l.setForeground(new Color(0x9AA0AA));
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.insets = new Insets(14, 0, 0, 0);
            panel.add(l, c);
            row++;
            GridBagConstraints filler = constraints(0);
            filler.weighty = 1;
            panel.add(Box.createVerticalGlue(), filler);
            row++;
        }

        private void add(String label, JComponent control) {
            panel.add(new JLabel(label), constraints(0));
            GridBagConstraints c = constraints(1);
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add(control, c);
            row++;
        }

        private GridBagConstraints constraints(int col) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = col;
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(4, col == 0 ? 0 : 12, 4, 0);
            return c;
        }
    }

    // ---------------------------------------------------------------- game file

    private Path currentGame() {
        String g = settings.get("game.jar", "");
        if (g.isEmpty()) {
            return null;
        }
        Path p = Paths.get(g);
        if (!p.isAbsolute()) {
            p = Launcher.HOME.resolve(p);
        }
        return Files.isRegularFile(p) ? p : null;
    }

    private void refreshGame() {
        Path jar = currentGame();
        if (jar == null) {
            jar = Launcher.findEmbeddedOrLocalJar();
        }
        if (jar == null) {
            gameIcon.setIcon(null);
            gameName.setText("Nenhum jogo importado");
            gameInfo.setText("Clique em Importar e escolha o .jar");
            playButton.setEnabled(false);
            return;
        }
        try {
            Map<String, String> m = Launcher.readManifest(jar);
            gameName.setText(m.getOrDefault("MIDlet-Name", "Jogo J2ME") + "  " + m.getOrDefault("MIDlet-Version", ""));
            gameInfo.setText("<html>" + jar.getFileName() + "<br>" + m.getOrDefault("MIDlet-Vendor", "") + "</html>");
            BufferedImage icon = readIcon(jar, m.get("MIDlet-Icon"));
            gameIcon.setIcon(icon == null ? null : new ImageIcon(icon.getScaledInstance(64, 64, Image.SCALE_SMOOTH)));
            playButton.setEnabled(true);
        } catch (IOException e) {
            gameName.setText("Arquivo inválido");
            gameInfo.setText(jar.getFileName().toString());
            playButton.setEnabled(false);
        }
    }

    private static BufferedImage readIcon(Path jar, String name) {
        if (name == null) {
            return null;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry e = jf.getEntry(name.startsWith("/") ? name.substring(1) : name);
            if (e == null) {
                return null;
            }
            try (InputStream in = jf.getInputStream(e)) {
                return ImageIO.read(in);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Copies the chosen jar into the program's games folder so it keeps working if the original moves. */
    private void importGame() {
        Path src = Launcher.chooseJar(frame);
        if (src == null) {
            return;
        }
        try {
            Path dir = Launcher.HOME.resolve("games");
            Files.createDirectories(dir);
            Path dst = dir.resolve(src.getFileName());
            if (!src.toAbsolutePath().equals(dst.toAbsolutePath())) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            settings.set("game.jar", "games/" + dst.getFileName());
            refreshGame();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Não foi possível importar:\n" + e.getMessage(),
                    "DiamondRushRecomp", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- controllers

    /** Lists connected gamepads; owns GLFW for the launcher's lifetime and shuts it down at the end. */
    private void pollPads() {
        try {
            if (!glfwInit()) {
                padStatus = "Suporte a controle indisponível";
                return;
            }
        } catch (Throwable t) {
            padStatus = "Suporte a controle indisponível";
            return;
        }
        try {
            while (polling) {
                glfwPollEvents();
                StringBuilder sb = new StringBuilder();
                for (int jid = 0; jid <= GLFW_JOYSTICK_LAST; jid++) {
                    if (glfwJoystickIsGamepad(jid)) {
                        sb.append(sb.length() == 0 ? "<html>" : "<br>").append("● ").append(glfwGetGamepadName(jid));
                    } else if (glfwJoystickPresent(jid)) {
                        sb.append(sb.length() == 0 ? "<html>" : "<br>").append("○ ").append(glfwGetJoystickName(jid))
                                .append(" (sem mapeamento)");
                    }
                }
                padStatus = sb.length() == 0 ? "Nenhum controle conectado (teclado)" : sb + "</html>";
                Thread.sleep(250);
            }
        } catch (InterruptedException ignored) {
            // closing
        } finally {
            glfwTerminate();
        }
    }
}
