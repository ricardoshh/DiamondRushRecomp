package javax.microedition.lcdui.game;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

import drpc.Runtime;

public abstract class GameCanvas extends Canvas {

    public static final int UP_PRESSED = 1 << UP;
    public static final int DOWN_PRESSED = 1 << DOWN;
    public static final int LEFT_PRESSED = 1 << LEFT;
    public static final int RIGHT_PRESSED = 1 << RIGHT;
    public static final int FIRE_PRESSED = 1 << FIRE;
    public static final int GAME_A_PRESSED = 1 << GAME_A;
    public static final int GAME_B_PRESSED = 1 << GAME_B;
    public static final int GAME_C_PRESSED = 1 << GAME_C;
    public static final int GAME_D_PRESSED = 1 << GAME_D;

    private final BufferedImage buffer;
    private volatile int keyStates;
    private volatile int latchedKeyStates;

    protected GameCanvas(boolean suppressKeyEvents) {
        buffer = new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = buffer.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, buffer.getWidth(), buffer.getHeight());
        g.dispose();
    }

    protected Graphics getGraphics() {
        javax.microedition.lcdui.DrawList.Sink sink = Runtime.recordingSink();
        return sink == null ? Graphics.forImage(buffer) : Graphics.forImage(buffer, sink);
    }

    public void flushGraphics() {
        if (isShown()) {
            Runtime.frameDone(buffer);
        }
    }

    public void flushGraphics(int x, int y, int w, int h) {
        flushGraphics();
    }

    public int getKeyStates() {
        int s = keyStates | latchedKeyStates;
        latchedKeyStates = 0;
        return s;
    }

    @Override
    public void paint(Graphics g) {
        // Canvas.paint is only used for plain canvases; a GameCanvas presents its buffer directly.
    }

    // Track key states for games that poll getKeyStates() instead of using keyPressed().
    public final void updateKeyState(int keyCode, boolean down) {
        int action = getGameAction(keyCode);
        if (action == 0) {
            return;
        }
        int bit = 1 << action;
        if (down) {
            keyStates |= bit;
            latchedKeyStates |= bit;
        } else {
            keyStates &= ~bit;
        }
    }
}
