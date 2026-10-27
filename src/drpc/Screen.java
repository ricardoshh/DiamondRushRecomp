package drpc;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
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
import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Desktop window: shows the 240x320 phone screen upscaled, handles fullscreen and
 * turns keyboard input into phone keys.
 */
final class Screen extends JPanel {

    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "blank");

    private final BufferedImage front =
            new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
    private final Upscaler upscaler = new Upscaler(Runtime.SCREEN_W, Runtime.SCREEN_H);
    private final Settings settings;
    private final JFrame frame;
    private final Map<Integer, Integer> heldKeys = new HashMap<>(); // AWT key code -> phone key
    private Upscaler.Filter filter;
    private boolean fullscreen;
    private Rectangle windowedBounds;
    private Runnable onClose = () -> System.exit(0);

    private String toast;
    private final Timer toastTimer;

    Screen(String title, Settings settings) {
        this.settings = settings;
        this.filter = Upscaler.Filter.fromId(settings.get("video.filter", "sharp"));
        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
        int scale = Math.max(1, Math.min((desktop.height - 120) / Runtime.SCREEN_H, (desktop.width - 40) / Runtime.SCREEN_W));
        setPreferredSize(new Dimension(Runtime.SCREEN_W * scale, Runtime.SCREEN_H * scale));

        toastTimer = new Timer(2000, e -> {
            toast = null;
            repaint();
        });
        toastTimer.setRepeats(false);

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

    void setOnClose(Runnable r) {
        onClose = r;
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
    }

    /** Shows a short message over the game. Safe to call from any thread. */
    void showToast(String text) {
        SwingUtilities.invokeLater(() -> {
            toast = text;
            toastTimer.restart();
            repaint();
        });
    }

    // ---------------------------------------------------------------- video

    void present(BufferedImage src) {
        synchronized (front) {
            Graphics g = front.getGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        synchronized (front) {
            upscaler.draw(g, front, filter, getWidth(), getHeight());
        }
        if (toast != null) {
            drawToast(g);
        }
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

    // ---------------------------------------------------------------- fullscreen

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

    // ---------------------------------------------------------------- input

    private void onKey(KeyEvent e, boolean down) {
        int vk = e.getKeyCode();
        if (down) {
            if (vk == KeyEvent.VK_F11 || (vk == KeyEvent.VK_ENTER && e.isAltDown())) {
                setFullscreen(!fullscreen);
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
                Input.press(code);
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
}
