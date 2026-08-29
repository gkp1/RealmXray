package packets.packetcapture.logger;

import com.google.gson.Gson;
import packets.Packet;
import packets.PacketType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Logger completo de pacotes: guarda cada frame (conhecido ou desconhecido) num ring buffer
 * limitado em memória e, opcionalmente, escreve um JSONL em disco.
 * Consumido pela aba "Packet Log" do front-end (branches tomato/potato).
 */
public class FullPacketLogger {
    public static final FullPacketLogger INSTANCE = new FullPacketLogger();

    private static final int DEFAULT_CAPACITY = 100_000;

    private final Gson gson = new Gson();
    private final int capacity;
    private final Deque<PacketLogEntry> ring = new ArrayDeque<>();
    private PrintWriter fileOut;

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
        // (detail view, or right below when saving to file).
        PacketLogEntry entry = new PacketLogEntry(System.currentTimeMillis(), incoming, typeId,
                typeName, size, deserialized != null, deserialized, raw);

        if (ring.size() >= capacity) {
            ring.removeFirst();
        }
        ring.addLast(entry);

        if (fileOut != null) {
            entry.getJson(); // force the lazy json before serializing the entry to disk
            fileOut.println(gson.toJson(entry));
            fileOut.flush();
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
     * Define um arquivo de saída JSONL (append). null desativa a escrita em arquivo.
     */
    public synchronized void setOutputFile(File f) throws IOException {
        if (fileOut != null) {
            fileOut.close();
            fileOut = null;
        }
        if (f != null) {
            fileOut = new PrintWriter(new FileWriter(f, true));
        }
    }

    public synchronized void close() {
        if (fileOut != null) {
            fileOut.close();
            fileOut = null;
        }
    }
}
