package javax.microedition.rms;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/**
 * RMS persisted as one file per record store in the save directory.
 * File format: int nextId, int count, then count x (int id, int len, bytes).
 */
public class RecordStore {

    public static final int AUTHMODE_PRIVATE = 0;
    public static final int AUTHMODE_ANY = 1;

    private static final Map<String, RecordStore> OPEN = new HashMap<>();

    private final String name;
    private final Path file;
    private final Map<Integer, byte[]> records = new LinkedHashMap<>(); // insertion order = id order
    private int nextId = 1;
    private int openCount;
    private int version;
    private long lastModified;

    private RecordStore(String name, Path file) {
        this.name = name;
        this.file = file;
    }

    private static Path fileFor(String name) {
        StringBuilder safe = new StringBuilder();
        for (char c : name.toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return drpc.Runtime.saveDir().resolve(safe + ".rms");
    }

    public static synchronized RecordStore openRecordStore(String name, boolean create)
            throws RecordStoreException {
        RecordStore rs = OPEN.get(name);
        if (rs == null) {
            Path f = fileFor(name);
            rs = new RecordStore(name, f);
            if (Files.exists(f)) {
                rs.load();
            } else if (!create) {
                throw new RecordStoreNotFoundException(name);
            } else {
                rs.save();
            }
            OPEN.put(name, rs);
        }
        rs.openCount++;
        return rs;
    }

    public static RecordStore openRecordStore(String name, boolean create, int authmode, boolean writable)
            throws RecordStoreException {
        return openRecordStore(name, create);
    }

    public static RecordStore openRecordStore(String name, String vendor, String suite)
            throws RecordStoreException {
        return openRecordStore(name, false);
    }

    public static synchronized void deleteRecordStore(String name) throws RecordStoreException {
        if (OPEN.containsKey(name)) {
            throw new RecordStoreException("record store is open: " + name);
        }
        try {
            if (!Files.deleteIfExists(fileFor(name))) {
                throw new RecordStoreNotFoundException(name);
            }
        } catch (IOException e) {
            throw new RecordStoreException(e.toString());
        }
    }

    public static String[] listRecordStores() {
        try (var s = Files.list(drpc.Runtime.saveDir())) {
            String[] names = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".rms"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .toArray(String[]::new);
            return names.length == 0 ? null : names;
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized void closeRecordStore() throws RecordStoreException {
        ensureOpen();
        if (--openCount == 0) {
            synchronized (RecordStore.class) {
                OPEN.remove(name);
            }
        }
    }

    public String getName() {
        return name;
    }

    public synchronized int getVersion() throws RecordStoreNotOpenException {
        ensureOpen();
        return version;
    }

    public synchronized long getLastModified() throws RecordStoreNotOpenException {
        ensureOpen();
        return lastModified;
    }

    public synchronized int getNumRecords() throws RecordStoreNotOpenException {
        ensureOpen();
        return records.size();
    }

    public synchronized int getNextRecordID() throws RecordStoreNotOpenException {
        ensureOpen();
        return nextId;
    }

    public synchronized int getSize() throws RecordStoreNotOpenException {
        ensureOpen();
        int size = 0;
        for (byte[] r : records.values()) {
            size += r.length;
        }
        return size;
    }

    public int getSizeAvailable() throws RecordStoreNotOpenException {
        ensureOpen();
        return 1 << 20;
    }

    public synchronized int addRecord(byte[] data, int offset, int len) throws RecordStoreException {
        ensureOpen();
        int id = nextId++;
        records.put(id, copy(data, offset, len));
        modified();
        return id;
    }

    public synchronized void setRecord(int id, byte[] data, int offset, int len) throws RecordStoreException {
        ensureOpen();
        if (!records.containsKey(id)) {
            throw new InvalidRecordIDException(String.valueOf(id));
        }
        records.put(id, copy(data, offset, len));
        modified();
    }

    public synchronized void deleteRecord(int id) throws RecordStoreException {
        ensureOpen();
        if (records.remove(id) == null) {
            throw new InvalidRecordIDException(String.valueOf(id));
        }
        modified();
    }

    public synchronized byte[] getRecord(int id) throws RecordStoreException {
        ensureOpen();
        byte[] r = records.get(id);
        if (r == null) {
            throw new InvalidRecordIDException(String.valueOf(id));
        }
        return r.length == 0 ? null : r.clone();
    }

    public synchronized int getRecord(int id, byte[] buffer, int offset) throws RecordStoreException {
        ensureOpen();
        byte[] r = records.get(id);
        if (r == null) {
            throw new InvalidRecordIDException(String.valueOf(id));
        }
        System.arraycopy(r, 0, buffer, offset, r.length);
        return r.length;
    }

    public synchronized int getRecordSize(int id) throws RecordStoreException {
        ensureOpen();
        byte[] r = records.get(id);
        if (r == null) {
            throw new InvalidRecordIDException(String.valueOf(id));
        }
        return r.length;
    }

    // ---------------------------------------------------------------- persistence

    private void ensureOpen() throws RecordStoreNotOpenException {
        if (openCount <= 0) {
            throw new RecordStoreNotOpenException(name);
        }
    }

    private static byte[] copy(byte[] data, int offset, int len) {
        byte[] r = new byte[len];
        if (data != null && len > 0) {
            System.arraycopy(data, offset, r, 0, len);
        }
        return r;
    }

    private void modified() throws RecordStoreException {
        version++;
        lastModified = System.currentTimeMillis();
        save();
    }

    private void load() throws RecordStoreException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            nextId = in.readInt();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int id = in.readInt();
                byte[] r = new byte[in.readInt()];
                in.readFully(r);
                records.put(id, r);
            }
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new RecordStoreException("corrupt record store " + name + ": " + e);
        }
    }

    private void save() throws RecordStoreException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(nextId);
                out.writeInt(records.size());
                for (Map.Entry<Integer, byte[]> e : records.entrySet()) {
                    byte[] r = e.getValue();
                    out.writeInt(e.getKey());
                    out.writeInt(r.length);
                    out.write(r);
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RecordStoreFullException("could not save " + name + ": " + e);
        }
    }
}
