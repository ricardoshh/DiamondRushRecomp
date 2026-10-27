package drpc;

import static org.lwjgl.glfw.GLFW.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * UI state of the game window: settings, options menu, toasts, controls legend and keyboard
 * mapping. Lives on the Swing thread; the window itself is a GLWindow, which forwards input here
 * and composites the two UI layers this class draws (under the picture: bezel and legend; over it:
 * menu and messages).
 */
final class Screen implements Input.Listener {

    private static final int[] SPEEDS = {50, 75, 100, 125, 150, 200};
    private static final int[] FPS = {20, 60, 120, 144, 240};
    private static final String[] GLYPH_PREFS = {"auto", "keyboard", "xbox", "playstation"};
    private static final String[] GLYPH_LABELS = {"Automático", "Teclado", "Xbox", "PlayStation"};

    private final Settings settings;
    private final String title;
    private final OptionsMenu menu;
    private final Map<Integer, Integer> heldKeys = new HashMap<>(); // GLFW key -> phone key
    private GLWindow window;

    // read by the render thread
    private volatile Upscaler.Filter filter;
    private volatile Upscaler.Background background;

    private boolean fullscreen;
    private boolean legend;
    private int glyphPref;
    private int speed;
    private int volume;
    private int windowScale;
    private int fbW;
    private int fbH;
    private Runnable onClose = () -> System.exit(0);
    private String gameJarName = "";

    private String toast;
    private final Timer toastTimer;

    Screen(String title, Settings settings) {
        this.title = title;
        this.settings = settings;
        filter = Upscaler.Filter.fromId(settings.get("video.filter", "sharp"));
        background = Upscaler.Background.fromId(settings.get("video.background", "blur"));
        legend = settings.getBool("input.legend", true);
        glyphPref = Math.max(0, Arrays.asList(GLYPH_PREFS).indexOf(settings.get("input.glyphs", "auto")));
        speed = clampSpeed((int) settings.getFloat("game.speed", 100));
        volume = Math.max(0, Math.min(100, (int) settings.getFloat("audio.volume", 100)));
        GameClock.setSpeed(speed / 100.0);
        Runtime.setMasterVolume(volume);
        windowScale = (int) settings.getFloat("video.windowScale", 0);
        if (windowScale < 1 || windowScale > maxWindowScale()) {
            windowScale = maxWindowScale();
        }

        toastTimer = new Timer(2500, e -> {
            toast = null;
            redrawLayers();
        });
        toastTimer.setRepeats(false);

        menu = new OptionsMenu(() -> {
            Input.releaseAllForGame();
            GameClock.setPaused(true);
        }, () -> GameClock.setPaused(false));
        buildMenu();
        Input.setListener(this);
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
            window.updateSwapInterval();
            settings.set("video.fps", String.valueOf(fps));
        });
        menu.value("Tela cheia", () -> fullscreen ? "Ligada" : "Desligada", d -> window.setFullscreen(!fullscreen));
        menu.value("Tamanho da janela",
                () -> windowScale + "x (" + Runtime.SCREEN_W * windowScale + "x" + Runtime.SCREEN_H * windowScale + ")",
                d -> setWindowScale(windowScale + d), () -> !fullscreen);
        menu.value("Velocidade do jogo", () -> speed + "%", d -> {
            int i = Arrays.binarySearch(SPEEDS, speed);
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

    /** Opens the window (call on the Swing thread). */
    void show(BufferedImage icon) {
        window = new GLWindow(this, settings, title, icon);
        window.start(Runtime.SCREEN_W * windowScale, Runtime.SCREEN_H * windowScale,
                settings.getBool("video.fullscreen", false));
        showToast(Input.device() == Input.Device.KEYBOARD ? "F12: opções" : "Guide ou Back+Start: opções");
    }

    Upscaler.Filter filter() {
        return filter;
    }

    Upscaler.Background background() {
        return background;
    }

    /** A finished game frame (game thread). */
    void present(BufferedImage frame) {
        GLWindow w = window;
        if (w != null) {
            w.present(frame);
        }
    }

    /** Shows a short message over the game. Safe to call from any thread. */
    void showToast(String text) {
        SwingUtilities.invokeLater(() -> {
            toast = text;
            toastTimer.restart();
            redrawLayers();
        });
    }

    // ---------------------------------------------------------------- window events (Swing thread)

    void onResize(int w, int h) {
        fbW = w;
        fbH = h;
        redrawLayers();
    }

    void onFullscreenChanged(boolean on) {
        fullscreen = on;
        settings.set("video.fullscreen", String.valueOf(on));
        redrawLayers();
    }

    void onFocusLost() {
        for (int code : heldKeys.values()) {
            Input.release(code); // avoid keys stuck down after Alt+Tab
        }
        heldKeys.clear();
    }

    void onIconify(boolean iconified) {
        Display.visibilityEvent(!iconified);
    }

    void onCloseRequest() {
        onClose.run();
    }

    void onKey(int key, int mods, boolean down) {
        if (down) {
            if (key == GLFW_KEY_F10) {
                filter = filter.next((mods & GLFW_MOD_SHIFT) != 0 ? -1 : 1);
                settings.set("video.filter", filter.id);
                showToast("Filtro: " + filter.label);
                return;
            }
            if (heldKeys.containsKey(key)) {
                return;
            }
            int code = phoneKey(key, mods);
            if (code != Input.NONE) {
                heldKeys.put(key, code);
                Input.press(code, Input.Device.KEYBOARD);
            }
        } else {
            Integer code = heldKeys.remove(key);
            if (code != null) {
                Input.release(code);
            }
        }
    }

    private static int phoneKey(int key, int mods) {
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW_KEY_UP: case GLFW_KEY_W: return Input.UP;
            case GLFW_KEY_DOWN: case GLFW_KEY_S: return Input.DOWN;
            case GLFW_KEY_LEFT: case GLFW_KEY_A: return Input.LEFT;
            case GLFW_KEY_RIGHT: case GLFW_KEY_D: return Input.RIGHT;
            case GLFW_KEY_ENTER: case GLFW_KEY_KP_ENTER: case GLFW_KEY_SPACE: return Input.OK;
            case GLFW_KEY_F1: case GLFW_KEY_Q: return Input.SOFT_LEFT;
            case GLFW_KEY_F2: case GLFW_KEY_E: case GLFW_KEY_ESCAPE: return Input.SOFT_RIGHT;
            case GLFW_KEY_F12: case GLFW_KEY_TAB: return Input.MENU;
            case GLFW_KEY_KP_MULTIPLY: return '*';
            case GLFW_KEY_KP_DIVIDE: return '#';
            case GLFW_KEY_8: if (shift) { return '*'; } break;
            case GLFW_KEY_3: if (shift) { return '#'; } break;
            default: break;
        }
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9 && !shift) {
            return '0' + (key - GLFW_KEY_0);
        }
        if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) {
            return '0' + (key - GLFW_KEY_KP_0);
        }
        return Input.NONE;
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
            redrawLayers();
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

    // ---------------------------------------------------------------- UI layers

    /** Redraws both UI layers for the current state and hands them to the window. */
    private void redrawLayers() {
        if (window == null || fbW <= 0 || fbH <= 0) {
            return;
        }
        Rectangle pic = Upscaler.layout(filter, background, fbW, fbH);
        boolean border = pic.width < fbW || pic.height < fbH;

        boolean bezel = background == Upscaler.Background.FRAME && border;
        boolean side = legend && !menu.isOpen() && pic.x >= legendSize() * 9;
        BufferedImage u = null;
        if (bezel || side) {
            u = layer();
            Graphics2D g = u.createGraphics();
            if (bezel) {
                Upscaler.drawFrame(g, pic);
            }
            if (side) {
                drawLegend(g, pic);
            }
            g.dispose();
        }

        BufferedImage o = null;
        if (menu.isOpen() || toast != null) {
            o = layer();
            Graphics2D g = o.createGraphics();
            if (menu.isOpen()) {
                menu.paint(g, fbW, fbH, glyphStyle());
            }
            if (toast != null) {
                drawToast(g);
            }
            g.dispose();
        }
        window.setLayers(u, o);
    }

    /** A new transparent layer of the window size (the render thread may still be reading the old one). */
    private BufferedImage layer() {
        return new BufferedImage(fbW, fbH, BufferedImage.TYPE_INT_ARGB);
    }

    private int legendSize() {
        return Math.max(18, Math.min(40, fbH / 26));
    }

    /** Control prompts for the active device, in the free space left of the picture. */
    private void drawLegend(Graphics2D g, Rectangle pic) {
        int space = pic.x;
        int size = legendSize();
        Glyphs.Style st = glyphStyle();
        boolean kb = st == Glyphs.Style.KEYBOARD;
        String[][] rows = kb
                ? new String[][] {{"arrows", "Mover"}, {"Enter", "Confirmar / ação"}, {"Esc", "Voltar"},
                    {"Q", "Menu do jogo"}, {"F12", "Opções"}, {"F11", "Tela cheia"}}
                : new String[][] {{"dpad", "Mover"}, {padButton(Input.OK, "a"), "Confirmar / ação"},
                    {padButton(Input.SOFT_RIGHT, "b"), "Voltar"}, {padButton(Input.SOFT_LEFT, "start"), "Menu do jogo"},
                    {"guide", "Opções"}};
        String head = kb ? "Teclado" : (st == Glyphs.Style.PLAYSTATION ? "Controle PlayStation" : "Controle Xbox");

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int line = size * 3 / 2;
        int x = Math.max(size, (space - size * 9) / 2);
        int y = fbH / 2 - (rows.length + 1) * line / 2;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size * 2 / 3));
        g.setColor(new Color(0xE8C04A));
        g.drawString(head, x, y + size / 2);
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
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = Math.max(14, fbH / 40);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(toast);
        int pad = size / 2;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(pad, pad, w + pad * 2, size + pad * 2, pad, pad);
        g.setColor(Color.WHITE);
        g.drawString(toast, pad * 2, pad * 2 + size - size / 5);
    }

    // ---------------------------------------------------------------- window size / game file

    private int maxWindowScale() {
        Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
        return Math.max(1, Math.min((desktop.height - 120) / Runtime.SCREEN_H, (desktop.width - 40) / Runtime.SCREEN_W));
    }

    private void setWindowScale(int k) {
        windowScale = Math.max(1, Math.min(maxWindowScale(), k));
        settings.set("video.windowScale", String.valueOf(windowScale));
        window.setWindowSize(Runtime.SCREEN_W * windowScale, Runtime.SCREEN_H * windowScale);
    }

    private void chooseGameJar() {
        if (fullscreen) {
            window.setFullscreen(false); // the file dialog would open behind a fullscreen window
        }
        Path jar = Launcher.chooseJar(null);
        if (jar == null) {
            return;
        }
        settings.set("game.jar", jar.toAbsolutePath().toString().replace('\\', '/'));
        showToast("Reiniciando com " + jar.getFileName() + "...");
        Launcher.relaunch();
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
