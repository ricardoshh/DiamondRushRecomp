package javax.microedition.midlet;

/** Lets the launcher call the protected lifecycle methods of a MIDlet. */
public final class MIDletBridge {

    private MIDletBridge() {
    }

    public static void start(MIDlet m) throws MIDletStateChangeException {
        m.startApp();
    }

    public static void pause(MIDlet m) {
        m.pauseApp();
    }

    public static void destroy(MIDlet m) throws MIDletStateChangeException {
        m.destroyApp(true);
    }
}
