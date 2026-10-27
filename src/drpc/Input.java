package drpc;

import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;

/**
 * Merges phone-key presses from all input sources (keyboard, gamepads) so that a key held
 * on two devices is pressed once and released only when the last one lets go.
 * Also tracks which device was used last (for on-screen button prompts) and routes keys to
 * the options menu while it is open. All methods must be called on the AWT event thread.
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
    /** Not a phone key: opens / closes the options menu. */
    static final int MENU = -1000;

    enum Device { KEYBOARD, GAMEPAD }

    interface Listener {
        void deviceChanged(Device device, String name);

        /** Returns true when the key was consumed by the options menu. */
        boolean menuKey(int code);
    }

    private static final Map<Integer, Integer> holders = new HashMap<>();
    private static Device device = Device.KEYBOARD;
    private static String padName = "";
    private static Listener listener;

    private Input() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static Device device() {
        return device;
    }

    static String padName() {
        return padName;
    }

    static void setPadName(String name) {
        padName = name == null ? "" : name;
    }

    static void press(int code, Device from) {
        if (from != device) {
            device = from;
            if (listener != null) {
                listener.deviceChanged(from, from == Device.GAMEPAD ? padName : "");
            }
        }
        if (code == NONE) {
            return;
        }
        if (listener != null && listener.menuKey(code)) {
            return;
        }
        if (code != MENU && holders.merge(code, 1, Integer::sum) == 1) {
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

    /** Releases every key the game currently sees as held (menu opened, focus lost). */
    static void releaseAllForGame() {
        for (int code : holders.keySet()) {
            Display.keyEvent(code, 1);
        }
        holders.clear();
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
            case "menu": case "options": return MENU;
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
