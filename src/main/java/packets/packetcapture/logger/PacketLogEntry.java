package packets.packetcapture.logger;

/**
 * Uma entrada individual de log de pacote (um frame ROTMG completo).
 */
public class PacketLogEntry {
    public final long timestamp;
    public final boolean incoming;   // true = servidor->cliente
    public final int typeId;
    public final String typeName;    // nome do enum (ex: "MOVE") ou "UNKNOWN"
    public final int size;           // tamanho do frame (header + payload)
    public final boolean deserialized; // true se o payload foi desserializado com sucesso
    public final String json;        // JSON do pacote desserializado (gson), "" se unknown/falhou
    public transient final byte[] raw; // bytes crus do frame (não serializados para arquivo)

    public PacketLogEntry(long timestamp, boolean incoming, int typeId, String typeName,
                          int size, boolean deserialized, String json, byte[] raw) {
        this.timestamp = timestamp;
        this.incoming = incoming;
        this.typeId = typeId;
        this.typeName = typeName;
        this.size = size;
        this.deserialized = deserialized;
        this.json = json;
        this.raw = raw;
    }
}
