package drpc;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;

/** MMAPI Player for audio/midi backed by javax.sound.midi (one shared synthesizer). */
public final class MidiPlayer implements Player, VolumeControl {

    private static final int END_OF_TRACK = 47;

    private static Synthesizer synth;
    private static Receiver synthReceiver;
    // Listener callbacks run off the sequencer thread, in order, like a phone's event queue.
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mmapi-events");
        t.setDaemon(true);
        return t;
    });

    private final byte[] data;
    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private Sequencer sequencer;
    private int state = UNREALIZED;
    private int loopCount = 1;
    private int level = 100;
    private boolean muted;
    private final int[] rawChannelVolume = new int[16];

    public MidiPlayer(byte[] data) {
        this.data = data;
        java.util.Arrays.fill(rawChannelVolume, 100);
    }

    private static synchronized Receiver synth() throws Exception {
        if (synth == null) {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            synthReceiver = synth.getReceiver();
        }
        return synthReceiver;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public synchronized void realize() throws MediaException {
        if (state == CLOSED) {
            throw new IllegalStateException("closed");
        }
        if (state >= REALIZED) {
            return;
        }
        try {
            Sequence seq = MidiSystem.getSequence(new ByteArrayInputStream(data));
            Receiver out = synth();
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            sequencer.setSequence(seq);
            sequencer.getTransmitter().setReceiver(new VolumeReceiver(out));
            sequencer.addMetaEventListener(this::onMeta);
        } catch (Exception e) {
            throw new MediaException(e.toString());
        }
        state = REALIZED;
    }

    @Override
    public synchronized void prefetch() throws MediaException {
        realize();
        if (state < PREFETCHED) {
            state = PREFETCHED;
        }
    }

    @Override
    public synchronized void start() throws MediaException {
        prefetch();
        if (state == STARTED) {
            return;
        }
        if (sequencer.getTickPosition() >= sequencer.getTickLength()) {
            sequencer.setTickPosition(0);
        }
        sequencer.setLoopCount(loopCount < 0 ? Sequencer.LOOP_CONTINUOUSLY : loopCount - 1);
        applyVolume();
        sequencer.start();
        state = STARTED;
        fire(PlayerListener.STARTED, getMediaTime());
    }

    @Override
    public synchronized void stop() {
        if (state != STARTED) {
            return;
        }
        sequencer.stop();
        allNotesOff();
        state = PREFETCHED;
        fire(PlayerListener.STOPPED, getMediaTime());
    }

    @Override
    public synchronized void deallocate() {
        if (state == STARTED) {
            stop();
        }
        if (state == PREFETCHED) {
            state = REALIZED;
        }
    }

    @Override
    public synchronized void close() {
        if (state == CLOSED) {
            return;
        }
        if (sequencer != null) {
            sequencer.stop();
            sequencer.close();
            allNotesOff();
        }
        state = CLOSED;
        fire(PlayerListener.CLOSED, null);
    }

    private void onMeta(MetaMessage m) {
        if (m.getType() != END_OF_TRACK) {
            return;
        }
        synchronized (this) {
            if (state != STARTED) {
                return;
            }
            state = PREFETCHED;
            sequencer.setTickPosition(0);
        }
        fire(PlayerListener.END_OF_MEDIA, getMediaTime());
    }

    private void fire(String event, Object data) {
        for (PlayerListener l : listeners) {
            EVENTS.execute(() -> l.playerUpdate(this, event, data));
        }
    }

    private static void allNotesOff() {
        Synthesizer s = synth;
        if (s == null) {
            return;
        }
        for (MidiChannel ch : s.getChannels()) {
            if (ch != null) {
                ch.allNotesOff();
                ch.allSoundOff();
            }
        }
    }

    // ---------------------------------------------------------------- misc Player API

    @Override
    public long setMediaTime(long now) throws MediaException {
        realize();
        sequencer.setMicrosecondPosition(Math.max(0, now));
        return sequencer.getMicrosecondPosition();
    }

    @Override
    public long getMediaTime() {
        Sequencer s = sequencer;
        return s == null ? TIME_UNKNOWN : s.getMicrosecondPosition();
    }

    @Override
    public long getDuration() {
        Sequencer s = sequencer;
        return s == null ? TIME_UNKNOWN : s.getMicrosecondLength();
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public void setLoopCount(int count) {
        if (count == 0) {
            throw new IllegalArgumentException();
        }
        loopCount = count;
    }

    @Override
    public String getContentType() {
        return "audio/midi";
    }

    @Override
    public void addPlayerListener(PlayerListener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    @Override
    public void removePlayerListener(PlayerListener l) {
        listeners.remove(l);
    }

    @Override
    public Control[] getControls() {
        return new Control[] {this};
    }

    @Override
    public Control getControl(String type) {
        if ("VolumeControl".equals(type) || "javax.microedition.media.control.VolumeControl".equals(type)) {
            return this;
        }
        return null;
    }

    // ---------------------------------------------------------------- VolumeControl

    @Override
    public synchronized int setLevel(int newLevel) {
        level = Math.max(0, Math.min(100, newLevel));
        applyVolume();
        fire(PlayerListener.VOLUME_CHANGED, this);
        return level;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public synchronized void setMute(boolean mute) {
        muted = mute;
        applyVolume();
    }

    @Override
    public boolean isMuted() {
        return muted;
    }

    private int effectiveLevel() {
        return muted ? 0 : level * Runtime.masterVolume() / 100;
    }

    private void applyVolume() {
        Synthesizer s = synth;
        if (s == null || state < REALIZED) {
            return;
        }
        MidiChannel[] chans = s.getChannels();
        for (int i = 0; i < chans.length && i < 16; i++) {
            if (chans[i] != null) {
                chans[i].controlChange(7, rawChannelVolume[i] * effectiveLevel() / 100);
            }
        }
    }

    /** Scales channel-volume (CC7) messages from the song by the player's volume level. */
    private final class VolumeReceiver implements Receiver {
        private final Receiver out;

        VolumeReceiver(Receiver out) {
            this.out = out;
        }

        @Override
        public void send(MidiMessage msg, long ts) {
            if (msg instanceof ShortMessage) {
                ShortMessage sm = (ShortMessage) msg;
                if (sm.getCommand() == ShortMessage.CONTROL_CHANGE && sm.getData1() == 7) {
                    int ch = sm.getChannel();
                    rawChannelVolume[ch] = sm.getData2();
                    try {
                        msg = new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7,
                                sm.getData2() * effectiveLevel() / 100);
                    } catch (Exception ignored) {
                        // keep the original message
                    }
                }
            }
            out.send(msg, ts);
        }

        @Override
        public void close() {
        }
    }
}
