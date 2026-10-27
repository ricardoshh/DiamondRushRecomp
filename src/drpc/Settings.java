package drpc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** User settings, stored as an editable properties file next to the launcher. */
final class Settings {

    private static final String TEMPLATE = String.join("\n",
            "# DiamondRushRecomp settings",
            "#",
            "# video.filter: pixel | sharp | smooth | scale2x | scale3x | scale4x  (F10 cycles in game)",
            "video.filter=sharp",
            "# video.fullscreen: true | false  (F11 / Alt+Enter toggles in game)",
            "video.fullscreen=false",
            "# video.background: black | blur | frame  (area around the 3:4 picture)",
            "video.background=blur",
            "# video.windowScale: window size multiplier when not fullscreen (0 = largest that fits)",
            "video.windowScale=0",
            "# video.fps: 20 = original (no interpolation) | 60 | 120 | 144 | 240",
            "video.fps=60",
            "",
            "# game.speed: percent of the original 20 ticks/s (50 75 100 125 150 200)",
            "game.speed=100",
            "audio.volume=100",
            "",
            "# input.glyphs: auto | keyboard | xbox | playstation   input.legend: show controls beside the game",
            "input.glyphs=auto",
            "input.legend=true",
            "",
            "# Gamepad buttons -> phone keys.",
            "# Phone keys: up down left right ok softleft softright 0-9 star pound menu none",
            "# Guide (or Back+Start together) always opens the options menu.",
            "pad.a=ok",
            "pad.b=softright",
            "pad.x=7",
            "pad.y=9",
            "pad.lb=star",
            "pad.rb=pound",
            "pad.lt=0",
            "pad.rt=5",
            "pad.start=softleft",
            "pad.back=softright",
            "pad.l3=none",
            "pad.r3=none",
            "# Analog stick threshold for directions (0.1 - 0.9)",
            "pad.deadzone=0.5",
            "");

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    static Settings load(Path file) {
        Settings s = new Settings(file);
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            }
        } catch (IOException e) {
            System.out.println("[drpc] could not read settings: " + e);
        }
        return s;
    }

    String get(String key, String def) {
        return props.getProperty(key, def).trim();
    }

    boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    float getFloat(String key, float def) {
        try {
            return Float.parseFloat(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Updates one key, rewriting only that line so comments and order survive. */
    synchronized void set(String key, String value) {
        props.setProperty(key, value);
        try {
            StringBuilder out = new StringBuilder();
            boolean found = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith(key + "=")) {
                    out.append(key).append('=').append(value);
                    found = true;
                } else {
                    out.append(line);
                }
                out.append('\n');
            }
            if (!found) {
                out.append(key).append('=').append(value).append('\n');
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(out.toString());
            }
        } catch (IOException e) {
            System.out.println("[drpc] could not save settings: " + e);
        }
    }
}
