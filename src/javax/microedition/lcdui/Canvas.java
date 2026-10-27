package javax.microedition.lcdui;

import java.awt.image.BufferedImage;

public abstract class Canvas extends Displayable {

    public static final int UP = 1;
    public static final int DOWN = 6;
    public static final int LEFT = 2;
    public static final int RIGHT = 5;
    public static final int FIRE = 8;
    public static final int GAME_A = 9;
    public static final int GAME_B = 10;
    public static final int GAME_C = 11;
    public static final int GAME_D = 12;

    public static final int KEY_NUM0 = 48;
    public static final int KEY_NUM1 = 49;
    public static final int KEY_NUM2 = 50;
    public static final int KEY_NUM3 = 51;
    public static final int KEY_NUM4 = 52;
    public static final int KEY_NUM5 = 53;
    public static final int KEY_NUM6 = 54;
    public static final int KEY_NUM7 = 55;
    public static final int KEY_NUM8 = 56;
    public static final int KEY_NUM9 = 57;
    public static final int KEY_STAR = 42;
    public static final int KEY_POUND = 35;

    protected Canvas() {
    }

    protected void keyPressed(int keyCode) {
    }

    protected void keyReleased(int keyCode) {
    }

    protected void keyRepeated(int keyCode) {
    }

    protected void pointerPressed(int x, int y) {
    }

    protected void pointerReleased(int x, int y) {
    }

    protected void pointerDragged(int x, int y) {
    }

    protected void showNotify() {
    }

    protected void hideNotify() {
    }

    /** Plain Canvas subclasses draw through paint(); GameCanvas overrides this. */
    protected abstract void paint(Graphics g);

    public void setFullScreenMode(boolean mode) {
    }

    public boolean hasPointerEvents() {
        return false;
    }

    public boolean hasPointerMotionEvents() {
        return false;
    }

    public boolean hasRepeatEvents() {
        return true;
    }

    public boolean isDoubleBuffered() {
        return true;
    }

    public int getGameAction(int keyCode) {
        switch (keyCode) {
            case -1: case KEY_NUM2: return UP;
            case -2: case KEY_NUM8: return DOWN;
            case -3: case KEY_NUM4: return LEFT;
            case -4: case KEY_NUM6: return RIGHT;
            case -5: case KEY_NUM5: return FIRE;
            default: return 0;
        }
    }

    public int getKeyCode(int gameAction) {
        switch (gameAction) {
            case UP: return -1;
            case DOWN: return -2;
            case LEFT: return -3;
            case RIGHT: return -4;
            case FIRE: return -5;
            default: return 0;
        }
    }

    public String getKeyName(int keyCode) {
        return String.valueOf(keyCode);
    }

    public final void repaint() {
        Display.requestRepaint(this);
    }

    public final void repaint(int x, int y, int w, int h) {
        repaint();
    }

    public final void serviceRepaints() {
        Display.serviceRepaints(this);
    }

    // ---- hooks used by Display (same package) ----

    void dispatchKeyPressed(int code) {
        keyPressed(code);
    }

    void dispatchKeyReleased(int code) {
        keyReleased(code);
    }

    void dispatchKeyRepeated(int code) {
        keyRepeated(code);
    }

    @Override
    void shown() {
        showNotify();
    }

    @Override
    void hidden() {
        hideNotify();
    }

    /** Renders this canvas into the given frame (used for plain, non-Game canvases). */
    void renderTo(BufferedImage frame) {
        java.awt.Graphics2D g2 = frame.createGraphics();
        try {
            paint(new Graphics(g2, frame.getWidth(), frame.getHeight()));
        } finally {
            g2.dispose();
        }
    }
}
