package javax.microedition.media;

import java.io.IOException;
import java.io.InputStream;

import drpc.MidiPlayer;

public final class Manager {

    public static final String TONE_DEVICE_LOCATOR = "device://tone";
    public static final String MIDI_DEVICE_LOCATOR = "device://midi";

    private Manager() {
    }

    public static Player createPlayer(InputStream stream, String type) throws IOException, MediaException {
        if (stream == null) {
            throw new IllegalArgumentException("null stream");
        }
        byte[] data = stream.readAllBytes();
        if (type == null || type.equals("audio/midi") || type.equals("audio/mid") || type.equals("audio/x-midi")) {
            return new MidiPlayer(data);
        }
        throw new MediaException("unsupported content type: " + type);
    }

    public static Player createPlayer(String locator) throws IOException, MediaException {
        throw new MediaException("unsupported locator: " + locator);
    }

    public static String[] getSupportedContentTypes(String protocol) {
        return new String[] {"audio/midi"};
    }

    public static String[] getSupportedProtocols(String contentType) {
        return new String[0];
    }

    public static void playTone(int note, int duration, int volume) throws MediaException {
    }
}
