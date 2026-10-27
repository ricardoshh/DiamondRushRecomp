package javax.microedition.lcdui;

public abstract class Displayable {

    Displayable() {
    }

    public int getWidth() {
        return drpc.Runtime.SCREEN_W;
    }

    public int getHeight() {
        return drpc.Runtime.SCREEN_H;
    }

    public boolean isShown() {
        return Display.current == this;
    }

    protected void sizeChanged(int w, int h) {
    }

    /** Called by Display when this becomes / stops being the visible screen. */
    void shown() {
    }

    void hidden() {
    }
}
