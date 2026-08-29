package packets.packetcapture.logger;

import com.google.gson.Gson;
import packets.Packet;

/**
 * Uma entrada individual de log de pacote (um frame ROTMG completo).
 * <p>
 * O JSON do pacote desserializado é caro de gerar (reflection via Gson) e a grande maioria dos
 * frames capturados (ex: spam de movimento) nunca é inspecionada. Por isso o campo {@code json}
 * é computado sob demanda na primeira chamada de {@link #getJson()} em vez de no momento da
 * captura — mantém o caminho quente de processamento de pacotes livre desse custo por frame.
 */
public class PacketLogEntry {
    private static final Gson GSON = new Gson();

    public final long timestamp;
    public final boolean incoming;   // true = servidor->cliente
    public final int typeId;
    public final String typeName;    // nome do enum (ex: "MOVE") ou "UNKNOWN"
    public final int size;           // tamanho do frame (header + payload)
    public final boolean deserialized; // true se o payload foi desserializado com sucesso
    public final transient byte[] raw; // bytes crus do frame (não serializados para arquivo)

    private final transient Packet packet; // fonte do JSON, não serializado diretamente
    private volatile String json; // cache preenchido por getJson(); serializado para arquivo quando presente

    public PacketLogEntry(long timestamp, boolean incoming, int typeId, String typeName,
                          int size, boolean deserialized, Packet packet, byte[] raw) {
        this.timestamp = timestamp;
        this.incoming = incoming;
        this.typeId = typeId;
        this.typeName = typeName;
        this.size = size;
        this.deserialized = deserialized;
        this.packet = packet;
        this.raw = raw;
    }

    /**
     * JSON (gson) do pacote desserializado, computado e cacheado na primeira chamada.
     * Retorna "" se unknown/falhou ao desserializar.
     */
    public String getJson() {
        String local = json;
        if (local == null) {
            if (deserialized && packet != null) {
                try {
                    local = GSON.toJson(packet);
                } catch (Exception ignored) {
                    local = "";
                }
            } else {
                local = "";
            }
            json = local;
        }
        return local;
    }
}
