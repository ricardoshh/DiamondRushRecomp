package drpc;

/**
 * Time source for the game's bytecode: System.currentTimeMillis, Thread.sleep and Thread.yield
 * are redirected here by GameClassLoader. This lets us change the game speed and freeze the
 * game exactly where it is while the options menu is open.
 */
public final class GameClock {

    private static final Object LOCK = new Object();
    private static double speed = 1.0;
    private static boolean paused;
    // virtual time = virtualBase + (now - realBase) * speed, frozen while paused
    private static long realBase = System.currentTimeMillis();
    private static double virtualBase = realBase;

    private GameClock() {
    }

    // ---------------------------------------------------------------- called by game bytecode

    public static long millis() {
        synchronized (LOCK) {
            return (long) virtualNow();
        }
    }

    public static void sleep(long ms) throws InterruptedException {
        waitWhilePaused();
        if (ms > 0) {
            Thread.sleep(Math.max(1, (long) (ms / speed)));
        }
        waitWhilePaused();
    }

    public static void yield() {
        try {
            waitWhilePaused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Thread.yield();
    }

    // ---------------------------------------------------------------- control

    static void setSpeed(double s) {
        synchronized (LOCK) {
            rebase();
            speed = s;
        }
    }

    static void setPaused(boolean p) {
        synchronized (LOCK) {
            if (paused == p) {
                return;
            }
            rebase();
            paused = p;
            LOCK.notifyAll();
        }
        if (p) {
            MidiPlayer.pauseAll();
        } else {
            MidiPlayer.resumeAll();
        }
    }

    private static double virtualNow() {
        if (paused) {
            return virtualBase;
        }
        return virtualBase + (System.currentTimeMillis() - realBase) * speed;
    }

    private static void rebase() {
        virtualBase = virtualNow();
        realBase = System.currentTimeMillis();
    }

    private static void waitWhilePaused() throws InterruptedException {
        synchronized (LOCK) {
            while (paused) {
                LOCK.wait();
            }
        }
    }
}
