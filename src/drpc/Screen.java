package drpc;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Desktop window: shows the 240x320 phone screen upscaled over a background, the options menu,
 * button prompts for the active input device, and turns keyboard input into phone keys.
 */
final class Screen extends JPanel implements Input.Listener {

    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "blank");
    private static final int[] SPEEDS = {50, 75, 100, 125, 150, 200};
    private static final int[] FPS = {20, 60, 120, 144, 240};
    private static final String[] GLYPH_PREFS = {"auto", "keyboard", "xbox", "playstation"};
    private static final String[] GLYPH_LABELS = {"Automático", "Teclado", "Xbox", "PlayStation"};

    // Triple buffering: the producer fills "back", the window paints "shown", "ready" sits between.
    // Only the pointer swaps are locked, so a slow paint never blocks the game or the presenter.
    private final Object swapLock = new Object();
    private BufferedImage back = newFrame();
    private BufferedImage ready = newFrame();
    private BufferedImage shown = newFrame();
    private boolean fresh;
    private final Upscaler upscaler = new Upscaler(Runtime.SCREEN_W, Runtime.SCREEN_H);
    private final Settings settings;
    private final JFrame frame;
    private final Map<Integer, Integer> heldKeys = new HashMap<>(); // AWT key code -> phone key
    private final OptionsMenu menu;

    private Upscaler.Filter filter;
    private Upscaler.Background background;
    private boolean fullscreen;
    private boolean legend;
    private int glyphPref;
    private int speed;
    private int volume;
    private int windowScale;
    private Rectangle windowedBounds;
    private Runnable onClose = () -> System.exit(0);
    private String gameJarName = "";

    private String toast;
    private final Timer toastTimer;

    Screen(String title, Settings settings) {
        this.settings = settings;
        filter = Upscaler.Filter.fromId(settings.get("video.filter", "sharp"));
        background = Upscaler.Background.fromId(settings.get("video.background", "blur"));
        legend = settings.getBool("input.legend", true);
        glyphPref = Math.max(0, java.util.Arrays.asList(GLYPH_PREFS).indexOf(settings.get("input.glyphs", "auto")));
        speed = clampSpeed((int) settings.getFloat("game.speed", 100));
        volume = Math.max(0, Math.min(100, (int) settings.getFloat("audio.volume", 100)));
        GameClock.setSpeed(speed / 100.0);
        Runtime.setMasterVolume(volume);

        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        windowScale = (int) settings.getFloat("video.windowScale", 0);
        if (windowScale < 1 || windowScale > maxWindowScale()) {
            windowScale = maxWindowScale();
        }
        setPreferredSize(new Dimension(Runtime.SCREEN_W * windowScale, Runtime.SCREEN_H * windowScale));

        toastTimer = new Timer(2500, e -> {
            toast = null;
            repaint();
        });
        toastTimer.setRepeats(false);

        menu = new OptionsMenu(() -> {
            Input.releaseAllForGame();
            GameClock.setPaused(true);
            repaint();
        }, () -> {
            GameClock.setPaused(false);
            repaint();
        });
        buildMenu();
        Input.setListener(this);

        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(this);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose.run();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                Display.visibilityEvent(false);
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                Display.visibilityEvent(true);
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                releaseAllKeys(); // avoid keys stuck down after Alt+Tab
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                onKey(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                onKey(e, false);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    setFullscreen(!fullscreen);
                }
            }
        });
    }

    private void buildMenu() {
        menu.action("Continuar", null, () -> menu.setOpen(false));
        menu.action("Arquivo do jogo", () -> gameJarName, this::chooseGameJar);
        menu.value("Filtro de imagem", () -> filter.label, d -> {
            filter = filter.next(d);
            settings.set("video.filter", filter.id);
        });
        menu.value("Fundo / bordas", () -> background.label, d -> {
            background = background.next(d);
            settings.set("video.background", background.id);
        });
        menu.value("Taxa de quadros", () -> Interpolator.targetFps() <= 20 ? "Original (20)" : Interpolator.targetFps() + " fps", d -> {
            int i = 0;
            while (i < FPS.length - 1 && FPS[i] < Interpolator.targetFps()) {
                i++;
            }
            int fps = FPS[Math.floorMod(i + d, FPS.length)];
            Interpolator.configure(fps);
            settings.set("video.fps", String.valueOf(fps));
        });
        menu.value("Tela cheia", () -> fullscreen ? "Ligada" : "Desligada", d -> setFullscreen(!fullscreen));
        menu.value("Tamanho da janela",
                () -> windowScale + "x (" + Runtime.SCREEN_W * windowScale + "x" + Runtime.SCREEN_H * windowScale + ")",
                d -> setWindowScale(windowScale + d), () -> !fullscreen);
        menu.value("Velocidade do jogo", () -> speed + "%", d -> {
            int i = java.util.Arrays.binarySearch(SPEEDS, speed);
            speed = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, i + d))];
            GameClock.setSpeed(speed / 100.0);
            settings.set("game.speed", String.valueOf(speed));
        });
        menu.value("Volume", () -> volume + "%", d -> {
            volume = Math.max(0, Math.min(100, volume + d * 10));
            Runtime.setMasterVolume(volume);
            settings.set("audio.volume", String.valueOf(volume));
        });
        menu.value("Ícones de botões", () -> GLYPH_LABELS[glyphPref], d -> {
            glyphPref = (glyphPref + d + GLYPH_PREFS.length) % GLYPH_PREFS.length;
            settings.set("input.glyphs", GLYPH_PREFS[glyphPref]);
        });
        menu.value("Controles na lateral", () -> legend ? "Mostrar" : "Ocultar", d -> {
            legend = !legend;
            settings.set("input.legend", String.valueOf(legend));
        });
        menu.action("Sair do jogo", null, () -> onClose.run());
    }

    void setOnClose(Runnable r) {
        onClose = r;
    }

    void setGameJar(Path jar) {
        gameJarName = jar.getFileName().toString();
    }

    void show(java.awt.Image icon) {
        if (icon != null) {
            frame.setIconImage(icon);
        }
        frame.setVisible(true);
        if (settings.getBool("video.fullscreen", false)) {
            setFullscreen(true);
        }
        requestFocusInWindow();
        showToast(Input.device() == Input.Device.KEYBOARD ? "F12: opções" : "Guide ou Back+Start: opções");
    }

    /** Shows a short message over the game. Safe to call from any thread. */
    void showToast(String text) {
        SwingUtilities.invokeLater(() -> {
            toast = text;
            toastTimer.restart();
            repaint();
        });
    }

    // ---------------------------------------------------------------- Input.Listener

    @Override
    public void deviceChanged(Input.Device device, String name) {
        showToast(device == Input.Device.GAMEPAD
                ? "Usando controle" + (name.isEmpty() ? "" : ": " + name)
                : "Usando teclado");
    }

    @Override
    public boolean menuKey(int code) {
        boolean used = menu.key(code);
        if (used) {
            repaint();
        }
        return used;
    }

    private Glyphs.Style glyphStyle() {
        switch (GLYPH_PREFS[glyphPref]) {
            case "keyboard": return Glyphs.Style.KEYBOARD;
            case "xbox": return Glyphs.Style.XBOX;
            case "playstation": return Glyphs.Style.PLAYSTATION;
            default:
                return Input.device() == Input.Device.KEYBOARD
                        ? Glyphs.Style.KEYBOARD : Glyphs.styleForPad(Input.padName());
        }
    }

    // ---------------------------------------------------------------- video

    private static BufferedImage newFrame() {
        return new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
    }

    void present(BufferedImage src) {
        back.setData(src.getRaster());
        synchronized (swapLock) {
            BufferedImage t = ready;
            ready = back;
            back = t;
            fresh = true;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        Rectangle pic;
        synchronized (swapLock) {
            if (fresh) {
                BufferedImage t = shown;
                shown = ready;
                ready = t;
                fresh = false;
            }
        }
        long p0 = System.nanoTime();
        pic = upscaler.draw(g, shown, filter, background, w, h);
        paintStats(System.nanoTime() - p0);
        if (legend && !menu.isOpen()) {
            drawLegend(g, pic);
        }
        if (menu.isOpen()) {
            menu.paint(g, w, h, glyphStyle());
        }
        if (toast != null) {
            drawToast(g);
        }
    }

    private static final boolean STATS = Boolean.getBoolean("drpc.stats");
    private int paints;
    private long paintNanos;
    private long paintStart;

    private void paintStats(long nanos) {
        if (!STATS) {
            return;
        }
        long now = System.nanoTime();
        if (paintStart == 0) {
            paintStart = now;
        }
        paintNanos += nanos;
        if (++paints % 120 == 0) {
            System.out.printf("[stats] window %.1f fps, scale %.2f ms%n", paints * 1e9 / (now - paintStart), paintNanos / 1e6 / paints);
            paints = 0;
            paintNanos = 0;
            paintStart = now;
        }
    }

    /** Control prompts for the active device, in the free space left of the picture. */
    private void drawLegend(Graphics2D g, Rectangle pic) {
        int space = pic.x;
        int size = Math.max(18, Math.min(40, getHeight() / 26));
        if (space < size * 9) {
            return;
        }
        Glyphs.Style st = glyphStyle();
        boolean kb = st == Glyphs.Style.KEYBOARD;
        String[][] rows = kb
                ? new String[][] {{"arrows", "Mover"}, {"Enter", "Confirmar / ação"}, {"Esc", "Voltar"},
                    {"Q", "Menu do jogo"}, {"F12", "Opções"}, {"F11", "Tela cheia"}}
                : new String[][] {{"dpad", "Mover"}, {padButton(Input.OK, "a"), "Confirmar / ação"},
                    {padButton(Input.SOFT_RIGHT, "b"), "Voltar"}, {padButton(Input.SOFT_LEFT, "start"), "Menu do jogo"},
                    {"guide", "Opções"}};
        String title = kb ? "Teclado" : (st == Glyphs.Style.PLAYSTATION ? "Controle PlayStation" : "Controle Xbox");

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int line = size * 3 / 2;
        int x = Math.max(size, (space - size * 9) / 2);
        int y = getHeight() / 2 - (rows.length + 1) * line / 2;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size * 2 / 3));
        g.setColor(new Color(0xE8C04A));
        g.drawString(title, x, y + size / 2);
        Font label = new Font(Font.SANS_SERIF, Font.PLAIN, size * 3 / 5);
        g.setFont(label);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < rows.length; i++) {
            int cy = y + (i + 1) * line + size / 4;
            int gw = Glyphs.draw(g, st, rows[i][0], x, cy, size);
            g.setFont(label);
            g.setColor(new Color(0xE6E6E6));
            g.drawString(rows[i][1], x + Math.max(gw, size * 2) + size / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
        }
    }

    private static String padButton(int code, String fallback) {
        String b = Gamepad.buttonFor(code);
        return b == null ? fallback : b;
    }

    private void drawToast(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = Math.max(14, getHeight() / 40);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(toast);
        int pad = size / 2;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(pad, pad, w + pad * 2, size + pad * 2, pad, pad);
        g.setColor(Color.WHITE);
        g.drawString(toast, pad * 2, pad * 2 + size - size / 5);
    }

    private void cycleFilter(int dir) {
        filter = filter.next(dir);
        settings.set("video.filter", filter.id);
        showToast("Filtro: " + filter.label);
    }

    // ---------------------------------------------------------------- window / fullscreen

    private int maxWindowScale() {
        Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
        return Math.max(1, Math.min((desktop.height - 120) / Runtime.SCREEN_H, (desktop.width - 40) / Runtime.SCREEN_W));
    }

    private void setWindowScale(int k) {
        windowScale = Math.max(1, Math.min(maxWindowScale(), k));
        settings.set("video.windowScale", String.valueOf(windowScale));
        if (!fullscreen) {
            setPreferredSize(new Dimension(Runtime.SCREEN_W * windowScale, Runtime.SCREEN_H * windowScale));
            frame.pack();
        }
    }

    /** Borderless fullscreen on the monitor the window is on (no video mode switch). */
    private void setFullscreen(boolean on) {
        if (on == fullscreen) {
            return;
        }
        fullscreen = on;
        Rectangle monitor = frame.getGraphicsConfiguration().getBounds();
        frame.dispose();
        if (on) {
            windowedBounds = frame.getBounds();
            frame.setUndecorated(true);
            frame.setBounds(monitor);
            setCursor(BLANK_CURSOR);
        } else {
            frame.setUndecorated(false);
            if (windowedBounds != null) {
                frame.setBounds(windowedBounds);
            } else {
                frame.pack();
                frame.setLocationRelativeTo(null);
            }
            setCursor(Cursor.getDefaultCursor());
        }
        frame.setVisible(true);
        frame.toFront();
        requestFocusInWindow();
        settings.set("video.fullscreen", String.valueOf(on));
    }

    private void chooseGameJar() {
        if (fullscreen) {
            setFullscreen(false); // file dialogs can hide behind a borderless fullscreen window
        }
        Path jar = Launcher.chooseJar(frame);
        if (jar == null) {
            return;
        }
        settings.set("game.jar", jar.toAbsolutePath().toString().replace('\\', '/'));
        showToast("Reiniciando com " + jar.getFileName() + "...");
        Launcher.relaunch();
    }

    // ---------------------------------------------------------------- input

    private void onKey(KeyEvent e, boolean down) {
        int vk = e.getKeyCode();
        if (down) {
            if (vk == KeyEvent.VK_F11 || (vk == KeyEvent.VK_ENTER && e.isAltDown())) {
                setFullscreen(!fullscreen);
                repaint();
                return;
            }
            if (vk == KeyEvent.VK_F10) {
                cycleFilter(e.isShiftDown() ? -1 : 1);
                return;
            }
            if (heldKeys.containsKey(vk)) {
                return; // OS auto-repeat
            }
            int code = phoneKey(e);
            if (code != Input.NONE) {
                heldKeys.put(vk, code);
                Input.press(code, Input.Device.KEYBOARD);
            }
        } else {
            Integer code = heldKeys.remove(vk);
            if (code != null) {
                Input.release(code);
            }
        }
    }

    private void releaseAllKeys() {
        for (int code : heldKeys.values()) {
            Input.release(code);
        }
        heldKeys.clear();
    }

    private static int phoneKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP: case KeyEvent.VK_W: return Input.UP;
            case KeyEvent.VK_DOWN: case KeyEvent.VK_S: return Input.DOWN;
            case KeyEvent.VK_LEFT: case KeyEvent.VK_A: return Input.LEFT;
            case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: return Input.RIGHT;
            case KeyEvent.VK_ENTER: case KeyEvent.VK_SPACE: return Input.OK;
            case KeyEvent.VK_F1: case KeyEvent.VK_Q: return Input.SOFT_LEFT;
            case KeyEvent.VK_F2: case KeyEvent.VK_E: case KeyEvent.VK_ESCAPE: return Input.SOFT_RIGHT;
            case KeyEvent.VK_F12: case KeyEvent.VK_TAB: return Input.MENU;
            case KeyEvent.VK_MULTIPLY: return '*';
            case KeyEvent.VK_DIVIDE: return '#';
            default: break;
        }
        int vk = e.getKeyCode();
        if (vk >= KeyEvent.VK_0 && vk <= KeyEvent.VK_9 && !e.isShiftDown()) {
            return '0' + (vk - KeyEvent.VK_0);
        }
        if (vk >= KeyEvent.VK_NUMPAD0 && vk <= KeyEvent.VK_NUMPAD9) {
            return '0' + (vk - KeyEvent.VK_NUMPAD0);
        }
        char c = e.getKeyChar();
        if (c == '*' || c == '#') {
            return c;
        }
        return Input.NONE;
    }

    private static int clampSpeed(int s) {
        for (int v : SPEEDS) {
            if (v >= s) {
                return v;
            }
        }
        return SPEEDS[SPEEDS.length - 1];
    }
}
