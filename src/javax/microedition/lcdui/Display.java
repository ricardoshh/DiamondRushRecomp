package javax.microedition.lcdui;

import java.awt.image.BufferedImage;

import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.midlet.MIDlet;

import drpc.Runtime;

public class Display {

    private static Display instance;
    static volatile Displayable current;

    private Display() {
    }

    public static synchronized Display getDisplay(MIDlet m) {
        if (instance == null) {
            instance = new Display();
        }
        return instance;
    }

    public void setCurrent(Displayable next) {
        Displayable prev = current;
        if (prev == next) {
            return;
        }
        current = next;
        if (prev != null) {
            prev.hidden();
        }
        if (next != null) {
            next.shown();
            if (next instanceof Canvas && !(next instanceof GameCanvas)) {
                requestRepaint((Canvas) next);
            }
        }
    }

    public Displayable getCurrent() {
        return current;
    }

    public boolean vibrate(int millis) {
        return Runtime.vibrate(millis);
    }

    public boolean flashBacklight(int millis) {
        return false;
    }

    public boolean isColor() {
        return true;
    }

    public int numColors() {
        return 1 << 16;
    }

    public int numAlphaLevels() {
        return 256;
    }

    public void callSerially(Runnable r) {
        Runtime.runOnEventThread(r);
    }

    // ---------------------------------------------------------------- package hooks

    static void requestRepaint(Canvas c) {
        if (current == c) {
            Runtime.runOnEventThread(() -> serviceRepaints(c));
        }
    }

    static void serviceRepaints(Canvas c) {
        if (current != c) {
            return;
        }
        BufferedImage frame = Runtime.frameScratch();
        c.renderTo(frame);
        Runtime.present(frame);
    }

    /** Called by the window when the user presses a key. */
    public static void keyEvent(int code, int type) {
        Displayable d = current;
        if (!(d instanceof Canvas)) {
            return;
        }
        Canvas c = (Canvas) d;
        if (c instanceof GameCanvas && type != 2) {
            ((GameCanvas) c).updateKeyState(code, type == 0);
        }
        switch (type) {
            case 0: c.dispatchKeyPressed(code); break;
            case 1: c.dispatchKeyReleased(code); break;
            default: c.dispatchKeyRepeated(code); break;
        }
    }

    /** Called by the window on minimize / focus changes. */
    public static void visibilityEvent(boolean visible) {
        Displayable d = current;
        if (d == null) {
            return;
        }
        if (visible) {
            d.shown();
        } else {
            d.hidden();
        }
    }
}
