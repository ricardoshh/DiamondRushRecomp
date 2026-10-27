package drpc;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import javax.microedition.lcdui.Display;
import javax.swing.JFrame;
import javax.swing.JPanel;

/** Desktop window: shows the 240x320 phone screen scaled up and turns keyboard input into phone keys. */
final class Screen extends JPanel {

    // Nokia S40 key codes (confirmed from the game's key translation table)
    static final int KEY_UP = -1;
    static final int KEY_DOWN = -2;
    static final int KEY_LEFT = -3;
    static final int KEY_RIGHT = -4;
    static final int KEY_FIRE = -5;
    static final int KEY_SOFT_LEFT = -6;
    static final int KEY_SOFT_RIGHT = -7;

    private final BufferedImage front =
            new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
    private final Set<Integer> held = new HashSet<>();
    private final JFrame frame;
    private Runnable onClose = () -> System.exit(0);
    private boolean fullscreen;

    Screen(String title) {
        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        Dimension desktop = Toolkit.getDefaultToolkit().getScreenSize();
        int scale = Math.max(1, Math.min((desktop.height - 120) / Runtime.SCREEN_H, (desktop.width - 40) / Runtime.SCREEN_W));
        setPreferredSize(new Dimension(Runtime.SCREEN_W * scale, Runtime.SCREEN_H * scale));

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
    }

    void setOnClose(Runnable r) {
        onClose = r;
    }

    void show(java.awt.Image icon) {
        if (icon != null) {
            frame.setIconImage(icon);
        }
        frame.setVisible(true);
        requestFocusInWindow();
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
        int w = getWidth();
        int h = getHeight();
        // Integer scale when it fits (crisp pixels), otherwise fit preserving aspect ratio.
        int iscale = Math.min(w / Runtime.SCREEN_W, h / Runtime.SCREEN_H);
        int dw;
        int dh;
        if (iscale >= 1) {
            dw = Runtime.SCREEN_W * iscale;
            dh = Runtime.SCREEN_H * iscale;
        } else {
            double s = Math.min((double) w / Runtime.SCREEN_W, (double) h / Runtime.SCREEN_H);
            dw = (int) (Runtime.SCREEN_W * s);
            dh = (int) (Runtime.SCREEN_H * s);
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        synchronized (front) {
            g.drawImage(front, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        }
    }

    private void toggleFullscreen() {
        GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        fullscreen = !fullscreen;
        frame.dispose();
        frame.setUndecorated(fullscreen);
        dev.setFullScreenWindow(fullscreen ? frame : null);
        if (!fullscreen) {
            frame.pack();
            frame.setLocationRelativeTo(null);
        }
        frame.setVisible(true);
        requestFocusInWindow();
    }

    // ---------------------------------------------------------------- input

    private void onKey(KeyEvent e, boolean down) {
        int vk = e.getKeyCode();
        if (down && (vk == KeyEvent.VK_F11 || (vk == KeyEvent.VK_ENTER && e.isAltDown()))) {
            toggleFullscreen();
            return;
        }
        int code = phoneKey(e);
        if (code == 0) {
            return;
        }
        if (down) {
            if (!held.add(vk)) {
                Display.keyEvent(code, 2); // OS auto-repeat -> keyRepeated
                return;
            }
            Display.keyEvent(code, 0);
        } else {
            held.remove(vk);
            Display.keyEvent(code, 1);
        }
    }

    private static int phoneKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP: case KeyEvent.VK_W: return KEY_UP;
            case KeyEvent.VK_DOWN: case KeyEvent.VK_S: return KEY_DOWN;
            case KeyEvent.VK_LEFT: case KeyEvent.VK_A: return KEY_LEFT;
            case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: return KEY_RIGHT;
            case KeyEvent.VK_ENTER: case KeyEvent.VK_SPACE: return KEY_FIRE;
            case KeyEvent.VK_F1: case KeyEvent.VK_Q: return KEY_SOFT_LEFT;
            case KeyEvent.VK_F2: case KeyEvent.VK_E: case KeyEvent.VK_ESCAPE: return KEY_SOFT_RIGHT;
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
        return 0;
    }
}
