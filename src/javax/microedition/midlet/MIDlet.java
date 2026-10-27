package javax.microedition.midlet;

import drpc.Runtime;

public abstract class MIDlet {

    protected MIDlet() {
    }

    protected abstract void startApp() throws MIDletStateChangeException;

    protected abstract void pauseApp();

    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public final String getAppProperty(String key) {
        return Runtime.getAppProperty(key);
    }

    public final void notifyDestroyed() {
        Runtime.exit();
    }

    public final void notifyPaused() {
    }

    public final void resumeRequest() {
    }

    public final boolean platformRequest(String url) {
        // The original game uses this for the "more games" link; nothing to open on PC.
        System.out.println("[midlet] platformRequest ignored: " + url);
        return false;
    }

    public final int checkPermission(String permission) {
        return 1;
    }
}
