package drpc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Options overlay drawn over the game (which is frozen while it is open). Navigated with
 * phone keys, so it works the same from the keyboard and from a gamepad.
 */
final class OptionsMenu {

    private static final Color PANEL = new Color(18, 20, 26, 235);
    private static final Color GOLD = new Color(0xE8C04A);
    private static final Color TEXT = new Color(0xF2F2F2);
    private static final Color DIM = new Color(0x9AA0AA);

    /** One row: a label, an optional value changed with left/right, an optional action on OK. */
    static final class Item {
        final String label;
        final Supplier<String> value;
        final IntConsumer change;
        final Runnable action;
        final BooleanSupplier enabled;

        Item(String label, Supplier<String> value, IntConsumer change, Runnable action, BooleanSupplier enabled) {
            this.label = label;
            this.value = value;
            this.change = change;
            this.action = action;
            this.enabled = enabled == null ? () -> true : enabled;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Runnable onOpen;
    private final Runnable onClose;
    private boolean open;
    private int selected;

    OptionsMenu(Runnable onOpen, Runnable onClose) {
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    /** Adds a value row (left/right changes it; OK steps forward). */
    void value(String label, Supplier<String> value, IntConsumer change) {
        items.add(new Item(label, value, change, null, null));
    }

    void value(String label, Supplier<String> value, IntConsumer change, BooleanSupplier enabled) {
        items.add(new Item(label, value, change, null, enabled));
    }

    /** Adds an action row. */
    void action(String label, Supplier<String> value, Runnable action) {
        items.add(new Item(label, value, null, action, null));
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean o) {
        if (o == open) {
            return;
        }
        open = o;
        if (o) {
            selected = 0;
            onOpen.run();
        } else {
            onClose.run();
        }
    }

    /** Handles a phone key while open. Returns true if it was consumed. */
    boolean key(int code) {
        if (code == Input.MENU) {
            setOpen(!open);
            return true;
        }
        if (!open) {
            return false;
        }
        Item it = items.get(selected);
        switch (code) {
            case Input.UP: case '2': move(-1); break;
            case Input.DOWN: case '8': move(1); break;
            case Input.LEFT: case '4':
                if (it.change != null && it.enabled.getAsBoolean()) {
                    it.change.accept(-1);
                }
                break;
            case Input.RIGHT: case '6':
                if (it.change != null && it.enabled.getAsBoolean()) {
                    it.change.accept(1);
                }
                break;
            case Input.OK: case '5':
                if (!it.enabled.getAsBoolean()) {
                    break;
                }
                if (it.action != null) {
                    it.action.run();
                } else if (it.change != null) {
                    it.change.accept(1);
                }
                break;
            case Input.SOFT_RIGHT:
                setOpen(false);
                break;
            default:
                break;
        }
        return true;
    }

    private void move(int dir) {
        int n = items.size();
        for (int i = 0; i < n; i++) {
            selected = (selected + dir + n) % n;
            if (items.get(selected).enabled.getAsBoolean()) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    void paint(Graphics2D g, int w, int h, Glyphs.Style style) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, w, h);

        int row = Math.max(22, Math.min(h / 18, 46));
        int fontSize = row * 11 / 20;
        int pw = Math.min(w - 20, Math.max(row * 15, w / 2));
        int ph = row * (items.size() + 3);
        if (ph > h - 20) {
            row = (h - 20) / (items.size() + 3);
            fontSize = row * 11 / 20;
            ph = row * (items.size() + 3);
        }
        int px = (w - pw) / 2;
        int py = (h - ph) / 2;
        int arc = row / 2;

        g.setColor(PANEL);
        g.fillRoundRect(px, py, pw, ph, arc, arc);
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(Math.max(1.5f, row / 16f)));
        g.drawRoundRect(px, py, pw, ph, arc, arc);

        Font bold = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize * 5 / 4));
        drawText(g, "Opções", px + row / 2, py + row * 3 / 4, GOLD);

        int pad = row / 2;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int y = py + row * (i + 1) + row / 4;
            int cy = y + row / 2;
            boolean on = it.enabled.getAsBoolean();
            if (i == selected) {
                g.setColor(new Color(0xE8C04A & 0xFFFFFF | 0x33000000, true));
                g.fillRoundRect(px + pad / 2, y + 2, pw - pad, row - 4, arc, arc);
            }
            g.setFont(i == selected ? bold : plain);
            drawText(g, it.label, px + pad, cy, on ? TEXT : DIM);
            if (it.value != null) {
                String v = it.value.get();
                if (it.change != null && on) {
                    v = "‹  " + v + "  ›";
                }
                FontMetrics fm = g.getFontMetrics();
                int room = pw - pad * 3 - fm.stringWidth(it.label);
                if (fm.stringWidth(v) > room) {
                    while (v.length() > 1 && fm.stringWidth(v + "…") > room) {
                        v = v.substring(0, v.length() - 1);
                    }
                    v += "…";
                }
                drawText(g, v, px + pw - pad - fm.stringWidth(v), cy, on ? (i == selected ? GOLD : TEXT) : DIM);
            }
        }

        // footer with prompts for the active device
        int fy = py + ph - row / 2 - row / 8;
        int gs = row * 3 / 4;
        int x = px + pad;
        g.setFont(plain);
        boolean kb = style == Glyphs.Style.KEYBOARD;
        x += Glyphs.draw(g, style, kb ? "arrows" : "dpad", x, fy, gs) + pad / 2;
        g.setFont(plain);
        x += drawText(g, "Navegar", x, fy, DIM) + pad;
        x += Glyphs.draw(g, style, kb ? "Enter" : "a", x, fy, gs) + pad / 2;
        g.setFont(plain);
        x += drawText(g, "Confirmar", x, fy, DIM) + pad;
        x += Glyphs.draw(g, style, kb ? "Esc" : "b", x, fy, gs) + pad / 2;
        g.setFont(plain);
        drawText(g, "Voltar", x, fy, DIM);
    }

    private static int drawText(Graphics2D g, String s, int x, int cy, Color c) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, x, cy + (fm.getAscent() - fm.getDescent()) / 2);
        return fm.stringWidth(s);
    }
}
