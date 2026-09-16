package packets.packetcapture.logger;

import com.google.gson.Gson;
import packets.Packet;
import packets.PacketType;
import util.DiagnosticLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/**
 * Logger completo de pacotes: guarda cada frame (conhecido ou desconhecido) num ring buffer
 * limitado em memória e escreve automaticamente um JSONL em disco (rx-logs/automatic, histórico
 * rotativo) para que uma sessão longa não fique só na RAM. "Save" copia o log automático atual
 * para rx-logs/saved.
 * Consumido pela aba "Packet Log" do front-end (branches tomato/potato).
 */
public class FullPacketLogger {
    public static final FullPacketLogger INSTANCE = new FullPacketLogger();

    private static final int DEFAULT_CAPACITY = 8_000;

    private static final File LOG_ROOT = new File("rx-logs");
    private static final File AUTOMATIC_DIR = new File(LOG_ROOT, "automatic");
    private static final File SAVED_DIR = new File(LOG_ROOT, "saved");
    private static final int MAX_AUTOMATIC_LOGS = 10;

    // Flush is batched instead of per-packet: after hours of play the automatic log file can get
    // large, and fsync-ing on every single frame (including high-frequency spam like movement)
    // is unnecessary disk churn. Flushing every N packets or every few seconds (whichever comes
    // first) keeps writes cheap while still bounding how much is lost if the app is killed.
    private static final int FLUSH_EVERY_N_PACKETS = 500;
    private static final long FLUSH_INTERVAL_MS = 3_000;

    private final Gson gson = new Gson();
    private final int capacity;
    private final Deque<PacketLogEntry> ring = new ArrayDeque<>();
    private PrintWriter fileOut;
    private File currentLogFile;
    private int unflushedCount;
    private long lastFlushTime;

    public FullPacketLogger() {
        this(DEFAULT_CAPACITY);
    }

    public FullPacketLogger(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Registra um frame. Chamado pelo PacketProcessor para TODOS os frames (conhecidos e desconhecidos).
     *
     * @param incoming     true se servidor->cliente.
     * @param typeId       id numérico do tipo.
     * @param size         tamanho do frame (header + payload).
     * @param raw          bytes crus do frame (payload já descriptografado, header intacto).
     * @param deserialized pacote desserializado (ou null se unknown/falhou).
     */
    public synchronized void onFrame(boolean incoming, int typeId, int size, byte[] raw, Packet deserialized) {
        String typeName = PacketType.containsKey(typeId) ? PacketType.byOrdinal(typeId).name() : "UNKNOWN";

        // Note: does NOT gson-serialize deserialized here. That reflection call is expensive and
        // this runs on the hot packet-processing path for every single frame (including
        // high-frequency spam like movement), regardless of whether anything ever looks at it.
        // PacketLogEntry.getJson() computes and caches it lazily, only when actually needed
        // (detail view, or right below when writing to file).
        PacketLogEntry entry = new PacketLogEntry(System.currentTimeMillis(), incoming, typeId,
                typeName, size, deserialized != null, deserialized, raw);

        if (ring.size() >= capacity) {
            ring.removeFirst();
        }
        ring.addLast(entry);

        if (fileOut != null) {
            entry.getJson(); // force the lazy json before serializing the entry to disk
            fileOut.println(gson.toJson(entry));
            unflushedCount++;
            long now = System.currentTimeMillis();
            if (unflushedCount >= FLUSH_EVERY_N_PACKETS || now - lastFlushTime >= FLUSH_INTERVAL_MS) {
                fileOut.flush();
                unflushedCount = 0;
                lastFlushTime = now;
            }
        }
    }

    /**
     * Retorna as N entradas mais recentes (mais antiga primeiro).
     */
    public synchronized List<PacketLogEntry> getRecent(int n) {
        List<PacketLogEntry> out = new ArrayList<>();
        int skip = Math.max(0, ring.size() - n);
        int i = 0;
        for (PacketLogEntry e : ring) {
            if (i++ < skip) continue;
            out.add(e);
        }
        return out;
    }

    /**
     * Retorna todas as entradas de um tipo específico (id numérico).
     */
    public synchronized List<PacketLogEntry> getByType(int typeId) {
        List<PacketLogEntry> out = new ArrayList<>();
        for (PacketLogEntry e : ring) {
            if (e.typeId == typeId) out.add(e);
        }
        return out;
    }

    /**
     * Número de entradas atualmente no ring buffer.
     */
    public synchronized int size() {
        return ring.size();
    }

    public synchronized void clear() {
        ring.clear();
    }

    /**
     * Starts writing every frame to a new timestamped file under rx-logs/automatic, enforcing a
     * rolling history of at most {@link #MAX_AUTOMATIC_LOGS} files (oldest deleted first). Meant
     * to be called once at boot so packet logs are always persisted to disk and never rely on the
     * in-memory ring buffer alone.
     */
    public synchronized void startAutomaticLogging() {
        try {
            if (!AUTOMATIC_DIR.exists() && !AUTOMATIC_DIR.mkdirs()) {
                DiagnosticLog.log("PACKET_LOG_AUTOMATIC_INIT_FAILED", "could not create " + AUTOMATIC_DIR.getAbsolutePath());
                return;
            }
            enforceRetention();

            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS").format(new Date());
            File newFile = new File(AUTOMATIC_DIR, "packet-log_" + stamp + ".jsonl");

            close();
            fileOut = new PrintWriter(new BufferedWriter(new FileWriter(newFile, false)), false);
            currentLogFile = newFile;
            unflushedCount = 0;
            lastFlushTime = System.currentTimeMillis();
        } catch (IOException e) {
            DiagnosticLog.log("PACKET_LOG_AUTOMATIC_INIT_FAILED", "failed to open new automatic log file", e);
        }
    }

    private void enforceRetention() {
        File[] existing = AUTOMATIC_DIR.listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (existing == null || existing.length < MAX_AUTOMATIC_LOGS) return;
        Arrays.sort(existing, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = existing.length - MAX_AUTOMATIC_LOGS + 1;
        for (int i = 0; i < toDelete; i++) {
            existing[i].delete();
        }
    }

    /**
     * Copies the current automatic log file into rx-logs/saved with a timestamped name, so it
     * survives the automatic log's rolling retention. Returns the saved file, or null if there is
     * no current automatic log yet.
     */
    public synchronized File saveCurrentLog() throws IOException {
        if (fileOut != null) fileOut.flush();
        if (currentLogFile == null || !currentLogFile.exists()) return null;

        if (!SAVED_DIR.exists() && !SAVED_DIR.mkdirs()) {
            throw new IOException("could not create " + SAVED_DIR.getAbsolutePath());
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss").format(new Date());
        File saved = new File(SAVED_DIR, "packet-log_" + stamp + ".jsonl");
        Files.copy(currentLogFile.toPath(), saved.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return saved;
    }

    /**
     * Path of the automatic log file currently being written to, or null if automatic logging
     * hasn't started yet.
     */
    public synchronized File getCurrentLogFile() {
        return currentLogFile;
    }

    public synchronized void close() {
        if (fileOut != null) {
            fileOut.flush();
            fileOut.close();
            fileOut = null;
        }
    }
}
