package drpc;

import static org.lwjgl.glfw.GLFW.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryUtil;

/**
 * Gamepad input through GLFW, which ships the SDL controller database (Xbox, PlayStation,
 * Switch Pro and many generic pads). Polls all pads on its own thread and feeds phone keys to Input.
 * An optional gamecontrollerdb.txt next to the launcher adds more mappings.
 */
final class Gamepad implements Runnable {

    private static final String[] BUTTON_KEYS = new String[GLFW_GAMEPAD_BUTTON_LAST + 1];

    static {
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_A] = "pad.a";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_B] = "pad.b";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_X] = "pad.x";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_Y] = "pad.y";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_LEFT_BUMPER] = "pad.lb";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER] = "pad.rb";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_START] = "pad.start";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_BACK] = "pad.back";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_LEFT_THUMB] = "pad.l3";
        BUTTON_KEYS[GLFW_GAMEPAD_BUTTON_RIGHT_THUMB] = "pad.r3";
    }

    private final Screen screen;
    private final int[] buttonMap = new int[GLFW_GAMEPAD_BUTTON_LAST + 1];
    private final int leftTrigger;
    private final int rightTrigger;
    private final float deadzone;

    private Gamepad(Settings s, Screen screen) {
        this.screen = screen;
        for (int b = 0; b < BUTTON_KEYS.length; b++) {
            buttonMap[b] = BUTTON_KEYS[b] == null ? Input.NONE : Input.parse(s.get(BUTTON_KEYS[b], "none"));
        }
        buttonMap[GLFW_GAMEPAD_BUTTON_DPAD_UP] = Input.UP;
        buttonMap[GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = Input.DOWN;
        buttonMap[GLFW_GAMEPAD_BUTTON_DPAD_LEFT] = Input.LEFT;
        buttonMap[GLFW_GAMEPAD_BUTTON_DPAD_RIGHT] = Input.RIGHT;
        leftTrigger = Input.parse(s.get("pad.lt", "none"));
        rightTrigger = Input.parse(s.get("pad.rt", "none"));
        deadzone = Math.max(0.1f, Math.min(0.9f, s.getFloat("pad.deadzone", 0.5f)));
    }

    static void start(Settings s, Screen screen) {
        Thread t = new Thread(new Gamepad(s, screen), "gamepad");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try {
            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                System.out.println("[drpc] gamepad support unavailable (glfwInit failed)");
                return;
            }
        } catch (Throwable t) {
            System.out.println("[drpc] gamepad support unavailable: " + t);
            return;
        }
        System.out.println("[drpc] gamepad support ready (GLFW " + glfwGetVersionString() + ")");
        loadExtraMappings();

        boolean[] connected = new boolean[GLFW_JOYSTICK_LAST + 1];
        Set<Integer> previous = new HashSet<>();
        try (GLFWGamepadState state = GLFWGamepadState.malloc()) {
            while (true) {
                glfwPollEvents(); // processes connect / disconnect
                Set<Integer> now = new HashSet<>();
                for (int jid = 0; jid <= GLFW_JOYSTICK_LAST; jid++) {
                    boolean pad = glfwJoystickIsGamepad(jid);
                    if (pad != connected[jid]) {
                        connected[jid] = pad;
                        String name = pad ? glfwGetGamepadName(jid) : null;
                        System.out.println("[drpc] gamepad " + jid + (pad ? " connected: " + name : " disconnected"));
                        screen.showToast(pad ? "Controle conectado: " + name : "Controle desconectado");
                    }
                    if (pad && glfwGetGamepadState(jid, state)) {
                        collect(state, now);
                    }
                }
                if (!now.equals(previous)) {
                    Set<Integer> pressed = new HashSet<>(now);
                    pressed.removeAll(previous);
                    Set<Integer> released = new HashSet<>(previous);
                    released.removeAll(now);
                    SwingUtilities.invokeLater(() -> {
                        released.forEach(Input::release);
                        pressed.forEach(Input::press);
                    });
                    previous = now;
                }
                Thread.sleep(8);
            }
        } catch (InterruptedException e) {
            // shutting down
        }
    }

    private void collect(GLFWGamepadState state, Set<Integer> out) {
        for (int b = 0; b <= GLFW_GAMEPAD_BUTTON_LAST; b++) {
            if (state.buttons(b) == GLFW_PRESS && buttonMap[b] != Input.NONE) {
                out.add(buttonMap[b]);
            }
        }
        // Left stick acts as a d-pad. Only the dominant axis counts: the game moves on a grid.
        float x = state.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
        float y = state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
        if (Math.max(Math.abs(x), Math.abs(y)) >= deadzone) {
            if (Math.abs(x) > Math.abs(y)) {
                out.add(x < 0 ? Input.LEFT : Input.RIGHT);
            } else {
                out.add(y < 0 ? Input.UP : Input.DOWN);
            }
        }
        // Triggers rest at -1 and go to +1 when fully pressed.
        if (state.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0 && leftTrigger != Input.NONE) {
            out.add(leftTrigger);
        }
        if (state.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0 && rightTrigger != Input.NONE) {
            out.add(rightTrigger);
        }
    }

    private static void loadExtraMappings() {
        Path db = Launcher.HOME.resolve("gamecontrollerdb.txt");
        if (!Files.exists(db)) {
            return;
        }
        try {
            ByteBuffer buf = MemoryUtil.memUTF8(Files.readString(db, StandardCharsets.UTF_8), true);
            try {
                boolean ok = glfwUpdateGamepadMappings(buf);
                System.out.println("[drpc] gamecontrollerdb.txt loaded: " + ok);
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (Exception e) {
            System.out.println("[drpc] could not load gamecontrollerdb.txt: " + e);
        }
    }
}
