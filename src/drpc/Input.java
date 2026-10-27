package drpc;

import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;

/**
 * Merges phone-key presses from all input sources (keyboard, gamepads) so that a key held
 * on two devices is pressed once and released only when the last one lets go.
 * All methods must be called on the AWT event thread.
 */
final class Input {

    // Nokia S40 key codes (confirmed from the game's key translation table)
    static final int UP = -1;
    static final int DOWN = -2;
    static final int LEFT = -3;
    static final int RIGHT = -4;
    static final int OK = -5;
    static final int SOFT_LEFT = -6;
    static final int SOFT_RIGHT = -7;
    static final int NONE = 0;

    private static final Map<Integer, Integer> holders = new HashMap<>();

    private Input() {
    }

    static void press(int code) {
        if (code != NONE && holders.merge(code, 1, Integer::sum) == 1) {
            Display.keyEvent(code, 0);
        }
    }

    static void release(int code) {
        Integer n = holders.get(code);
        if (n == null) {
            return;
        }
        if (n == 1) {
            holders.remove(code);
            Display.keyEvent(code, 1);
        } else {
            holders.put(code, n - 1);
        }
    }

    /** Parses a phone key name from the settings file. */
    static int parse(String name) {
        switch (name.toLowerCase()) {
            case "up": return UP;
            case "down": return DOWN;
            case "left": return LEFT;
            case "right": return RIGHT;
            case "ok": case "fire": return OK;
            case "softleft": return SOFT_LEFT;
            case "softright": return SOFT_RIGHT;
            case "star": case "*": return '*';
            case "pound": case "#": return '#';
            default:
                if (name.length() == 1 && Character.isDigit(name.charAt(0))) {
                    return name.charAt(0);
                }
                return NONE;
        }
    }
}
