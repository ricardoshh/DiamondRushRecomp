package javax.microedition.lcdui.game;

/**
 * Only the transform constants are provided; Diamond Rush draws its own sprites with
 * Graphics.drawRegion and never instantiates Sprite.
 */
public final class Sprite {

    public static final int TRANS_NONE = 0;
    public static final int TRANS_MIRROR_ROT180 = 1;
    public static final int TRANS_MIRROR = 2;
    public static final int TRANS_ROT180 = 3;
    public static final int TRANS_MIRROR_ROT270 = 4;
    public static final int TRANS_ROT90 = 5;
    public static final int TRANS_ROT270 = 6;
    public static final int TRANS_MIRROR_ROT90 = 7;

    private Sprite() {
    }
}
