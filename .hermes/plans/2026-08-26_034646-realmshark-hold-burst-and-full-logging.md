# Plano de Extensão RealmShark — Hold/Burst de pacotes + Log completo

> **Para o Hermes:** usar o skill `subagent-driven-development` para implementar task-a-task.

**Goal:** (1) adicionar capacidade de "segurar" (hold) um tipo específico de pacote e liberá-lo em rajada (burst) após alguns segundos, deixando todos os outros passarem normalmente; (2) adicionar um logger que registra TODOS os pacotes (conteúdo), não só estatísticas de bytes.

**Architecture:** O logger é uma extensão direta do pipeline atual (novo subscriber + hook no `PacketProcessor`). O hold/burst NÃO é possível no sniffer passivo atual — exige um **proxy TCP local** (man-in-the-middle) entre o cliente e o servidor. Porém, como o header `[size][type]` do ROTMG é **plaintext** (só o payload é RC4), o proxy consegue segurar/liberar pacotes **sem precisar de criptografia nenhuma** — ele apenas fatia os frames pelo header e reencaminha os bytes originais atrasados.

**Tech Stack:** Java 8, Gradle (shadowJar), pcap-spi 1.4.2 (sniffer), gson 2.9.1 (serialização do log), RC4 interno (`packets.packetcapture.encryption.RC4`).

---

## Descobertas que mudam o design (confirmadas no código)

1. **Não é TLS.** A criptografia é **RC4** (stream cipher), com chaves já hardcoded em `packets/packetcapture/encryption/RotMGRC4Keys.java` (`INCOMING_STRING`, `OUTGOING_STRING`). Não há TLS em lugar nenhum do pipeline.

2. **O header do frame é plaintext.** Em `PacketConstructor.packetReceived()` (`packets/packetcapture/pconstructor/PacketConstructor.java:59-73`):
   ```java
   int size = encryptedData.getInt();                 // [0..3] plaintext
   int type = Byte.toUnsignedInt(encryptedData.get()); // [4]   plaintext
   ...
   rc4Cipher.decrypt(5, encryptedData);               // só o payload (offset 5+) é RC4
   ```
   O `ROTMGPacketConstructor` também lê o `size` via `Util.decodeInt(bytes)` sem descriptografar. Ou seja, o formato no wire é `[size:4 bytes BE][type:1 byte][payload: size-5 bytes RC4]`. A keystream do RC4 avança **apenas sobre o payload**, continuamente entre frames.

   **Consequência:** o proxy de hold/burst **não precisa de RC4** para funcionar — só precisa ler o header plaintext para saber o `type`. O RC4 só é necessário se quisermos (a) descriptografar o payload para o logger (já feito pelo sniffer) ou (b) modificar o payload no proxy (fora do escopo).

3. **O sniffer atual é passivo (observe-only).** `Sniffer` usa pcap para capturar; não há nenhum `pcap_sendpacket`, `serialize`, `BufferWriter` ou método de envio em todo o repo (grep confirmou: `Packet` só tem `deserialize`). Portanto, **não dá para "segurar" um pacote que já foi enviado à rede** — para hold/burst é obrigatório estar no caminho do tráfego (proxy ou WinDivert).

4. **`PacketLogger` atual só agrega estatísticas.** `packets/packetcapture/logger/PacketLogger.java` guarda contagem de bytes e total por tipo; **não** loga pacotes individuais nem o conteúdo. A reclamação do usuário é procedente.

5. **Pacotes de tipo desconhecido são descartados.** Em `PacketProcessor.processPackets()` (`PacketProcessor.java:125-128`), se `!PacketType.containsKey(type)` o frame é impresso em `stderr` e descartado — não chega ao `Register` nem a nenhum logger. Um "log de todos os pacotes" precisa capturar também esses.

---

## Parte A — Log completo de pacotes (feature segura, realmshark branch)

### A.1 — Nova classe: `PacketLogEntry`

**Create:** `src/main/java/packets/packetcapture/logger/PacketLogEntry.java`

Entrada imutável de log por frame:

```java
package packets.packetcapture.logger;

public class PacketLogEntry {
    public final long timestamp;
    public final boolean incoming;   // true = servidor->cliente
    public final int typeId;
    public final String typeName;    // "MOVE" etc, ou "UNKNOWN" se não mapeado
    public final int size;           // size do frame (header + payload)
    public final String json;        // JSON do pacote desserializado (gson), ou "" se unknown/falhou
    public final byte[] raw;         // bytes crus do frame (header + payload)
    public final boolean deserialized;

    public PacketLogEntry(long ts, boolean incoming, int typeId, String typeName,
                          int size, byte[] raw, String json, boolean deserialized) { ... }
}
```

### A.2 — Nova classe: `FullPacketLogger`

**Create:** `src/main/java/packets/packetcapture/logger/FullPacketLogger.java`

Singleton com ring buffer limitado (default 100k entradas) + escrita opcional em JSONL via gson:

```java
package packets.packetcapture.logger;

import com.google.gson.Gson;
import packets.Packet;
import packets.PacketType;
import packets.packetcapture.PacketProcessor; // (ver A.4)
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FullPacketLogger {
    public static final FullPacketLogger INSTANCE = new FullPacketLogger();
    private final Gson gson = new Gson();
    private final Deque<PacketLogEntry> ring = new ArrayDeque<>();
    private final int capacity;
    private PrintWriter fileOut;   // null = sem arquivo

    public FullPacketLogger() { this(100_000); }
    public FullPacketLogger(int capacity) { this.capacity = capacity; }

    // Chamado pelo PacketProcessor para TODOS os frames (known + unknown).
    public synchronized void onFrame(boolean incoming, int typeId, int size, byte[] raw, Packet deserialized) {
        String typeName = PacketType.containsKey(typeId) ? PacketType.byOrdinal(typeId).name() : "UNKNOWN";
        String json = "";
        boolean ok = false;
        if (deserialized != null) {
            try { json = gson.toJson(deserialized); ok = true; } catch (Exception ignored) {}
        }
        PacketLogEntry e = new PacketLogEntry(System.currentTimeMillis(), incoming, typeId,
                typeName, size, raw, json, ok);
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(e);
        if (fileOut != null) { fileOut.println(gson.toJson(e)); fileOut.flush(); }
    }

    public synchronized List<PacketLogEntry> getRecent(int n) { ... }
    public synchronized List<PacketLogEntry> getByType(int typeId) { ... }
    public synchronized void clear() { ring.clear(); }
    public synchronized void setOutputFile(File f) throws IOException {
        if (fileOut != null) fileOut.close();
        fileOut = (f == null) ? null : new PrintWriter(new FileWriter(f, true));
    }
}
```

> `gson.toJson(packet)` serializa os campos públicos de qualquer `Packet` automaticamente — não precisamos escrever `toString()` em ~150 classes.

### A.3 — Propagar `Direction` pelo pipeline

**Modify:** `src/main/java/packets/packetcapture/pconstructor/PacketConstructor.java`

Adicionar campo + getter, e passar direção ao `processPackets`:

```java
public class PacketConstructor {
    private final boolean incoming; // true = servidor->cliente (usa INCOMING_STRING)
    ...
    public PacketConstructor(PacketProcessor pp, RC4 r, boolean incoming) {
        ...
        this.incoming = incoming;
    }
    public boolean isIncoming() { return incoming; }
    ...
    packetProcessor.processPackets(type, size, encryptedData, incoming);
}
```

**Modify:** `src/main/java/packets/packetcapture/PacketProcessor.java` (construtor, linhas 39-40):

```java
incomingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.INCOMING_STRING), true);
outgoingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.OUTGOING_STRING), false);
```

### A.4 — Hook de logging em `processPackets`

**Modify:** `src/main/java/packets/packetcapture/PacketProcessor.java:124-145`

Capturar o frame **antes** do early-return de tipo desconhecido, e registrar o objeto desserializado quando houver:

```java
public void processPackets(int type, int size, ByteBuffer data, boolean incoming) {
    byte[] raw = data.array();
    Packet deserialized = null;

    if (!PacketType.containsKey(type)) {
        System.err.println("Unknown packet type:" + type + " Data:" + Arrays.toString(raw));
        FullPacketLogger.INSTANCE.onFrame(incoming, type, size, raw, null);
        return;
    }

    logger.addPacket(type, size);
    Packet packetType = PacketType.getPacket(type).factory();
    packetType.setData(raw);
    BufferReader pData = new BufferReader(data);
    try {
        packetType.deserialize(pData);
        if (!pData.isBufferFullyParsed()) pData.printError(packetType);
        deserialized = packetType;
    } catch (Exception e) {
        Util.printLogs("Buffer exploded: " + pData.getIndex() + "/" + pData.size());
        debugPackets(type, raw);
    }
    FullPacketLogger.INSTANCE.onFrame(incoming, type, size, raw, deserialized);
    if (deserialized != null) Register.INSTANCE.emitPacketLogs(deserialized);
}
```

> `raw = data.array()` retorna o frame completo **ainda criptografado no payload** (porque a decriptação acontece in-place no mesmo buffer antes do `processPackets` — na verdade `decrypt(5,...)` muta o array; o payload já chega descriptografado aqui). Se quisermos os bytes crus de wire no log, precisamos copiar o array em `PacketConstructor.packetReceived` **antes** do `decrypt`. Decisão: logar os bytes **pós-decriptação** (mais úteis para inspeção) e manter o header intacto.

### A.5 — Aba "Packet Log" (na branch GUI: tomato/potato — fora do realmshark)

A branch `realmshark` não tem GUI; a aba vive no front-end. API que ela consome (já entregue por A.1-A.4):

- `FullPacketLogger.INSTANCE.getRecent(n)` → preencher uma `JTable` (colunas: hora, dir, type, size, deserialized).
- Painel de detalhe mostra `entry.json` (ou `Util.byteArrayPrint(entry.raw)` se falhou).
- Campo de filtro por nome de tipo.
- Checkbox "salvar em arquivo" → `setOutputFile(...)`.

---

## Parte B — Hold/Burst de pacotes (requer proxy; feature nova, realmshark branch)

### B.0 — Por que proxy (e por que não o sniffer)

O sniffer é passivo: vê o pacote depois que a rede já o enviou. "Segurar e liberar depois" exige estar no caminho. Abordagens possíveis:

| Abordagem | Esforço | Precisa mudar o cliente? | Nota |
|---|---|---|---|
| **Proxy TCP local** (recomendado) | médio | Sim (apontar p/ 127.0.0.1) | Reusa Java puro, sem driver extra |
| WinDivert/WFP redirect | alto | Não (transparente) | Precisa driver kernel + admin |
| Injeção via pcap_sendpacket + ARP spoof | alto | Não | Frágil p/ tráfego internet |

Como o header é plaintext, o proxy **não toca em RC4** para hold/burst — apenas fatia frames e reencaminha os bytes originais (atrasados). Isso preserva a keystream do servidor automaticamente, porque a ordem dos bytes não muda, só o tempo.

### B.1 — `ProxyConfig`

**Create:** `src/main/java/packets/packetcapture/proxy/ProxyConfig.java`

```java
public class ProxyConfig {
    public String listenHost = "127.0.0.1";
    public int    listenPort = 2050;
    public String upstreamHost;        // IP real do game server
    public int    upstreamPort = 2050;
    public final Set<Integer> holdIncoming = new HashSet<>();  // type ids a segurar (servidor->cliente)
    public final Set<Integer> holdOutgoing = new HashSet<>();  // type ids a segurar (cliente->servidor)
    public long   burstIntervalMs = 3000;   // intervalo da rajada
    public boolean holdAllThenBurst = false; // futuro: segurar tudo
}
```

### B.2 — `FrameSplitter`

**Create:** `src/main/java/packets/packetcapture/proxy/FrameSplitter.java`

Adaptação do `ROTMGPacketConstructor` que devolve os frames crus (sem decrypt), por direção:

```java
public class FrameSplitter {
    private final byte[] bytes = new byte[200000];
    private int index, pSize;

    /** Alimenta bytes e devolve a lista de frames completos encontrados. */
    public List<byte[]> feed(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        for (byte b : data) {
            bytes[index++] = b;
            if (index >= 4 && pSize == 0) {
                pSize = Util.decodeInt(bytes);
                if (pSize <= 0 || pSize > 200000) { pSize = 0; index = 0; continue; }
            }
            if (pSize != 0 && index == pSize) {
                frames.add(Arrays.copyOfRange(bytes, 0, pSize));
                index = 0; pSize = 0;
            }
        }
        return frames;
    }
}
```

### B.3 — `BurstQueue`

**Create:** `src/main/java/packets/packetcapture/proxy/BurstQueue.java`

Fila thread-safe + flusher periódico:

```java
public class BurstQueue {
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private ScheduledExecutorService scheduler;
    private final long intervalMs;

    public BurstQueue(long intervalMs) { this.intervalMs = intervalMs; }

    public synchronized void enqueue(byte[] frame) { queue.addLast(frame); }

    public synchronized List<byte[]> drain() {
        if (queue.isEmpty()) return Collections.emptyList();
        List<byte[]> out = new ArrayList<>(queue);
        queue.clear();
        return out;
    }

    public void start(Runnable onBurst) {   // onBurst escreve os frames drenados no socket
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            List<byte[]> frames = drain();
            if (!frames.isEmpty()) onBurst.run();   // ver B.4: escreve em ordem
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() { if (scheduler != null) scheduler.shutdownNow(); }
}
```

> Detalhe: `onBurst` precisa receber os frames drenados. Melhor assinatura: `start(Consumer<List<byte[]>> onBurst)`. Ajustar no código final.

### B.4 — `Relay` (um por direção)

**Create:** `src/main/java/packets/packetcapture/proxy/Relay.java`

Lê de um `InputStream`, fatia frames, decide hold vs pass-through:

```java
public class Relay extends Thread {
    private final InputStream in;
    private final OutputStream out;
    private final FrameSplitter splitter = new FrameSplitter();
    private final boolean incoming;               // qual direção (p/ escolher o hold set)
    private final ProxyConfig cfg;
    private final BurstQueue burst;
    private volatile boolean running = true;

    public void run() {
        byte[] buf = new byte[8192];
        try {
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                for (byte[] frame : splitter.feed(Arrays.copyOfRange(buf, 0, n))) {
                    int type = Byte.toUnsignedInt(frame[4]); // header plaintext
                    Set<Integer> hold = incoming ? cfg.holdIncoming : cfg.holdOutgoing;
                    if (hold.contains(type)) {
                        burst.enqueue(frame);      // segura; o flusher libera em rajada
                    } else {
                        out.write(frame);          // passa direto
                    }
                }
                out.flush();
            }
        } catch (IOException e) { /* socket fechado */ }
    }
    public void shutdown() { running = false; }
}
```

### B.5 — `ProxyServer`

**Create:** `src/main/java/packets/packetcapture/proxy/ProxyServer.java`

Aceita o cliente, abre o upstream, liga os dois relays e o burst:

```java
public class ProxyServer {
    private final ProxyConfig cfg;
    public ProxyServer(ProxyConfig cfg) { this.cfg = cfg; }

    public void start() throws IOException {
        try (ServerSocket server = new ServerSocket(cfg.listenPort, 50,
                InetAddress.getByName(cfg.listenHost))) {
            Socket client = server.accept();
            Socket upstream = new Socket(cfg.upstreamHost, cfg.upstreamPort);

            // cláusula: o burst escreve direto no socket de destino
            BurstQueue burst = new BurstQueue(cfg.burstIntervalMs);
            burst.start(frames -> { try { for (byte[] f : frames) upstream.getOutputStream().write(f);
                                         upstream.getOutputStream().flush(); } catch (IOException e) {} });

            Relay c2s = new Relay(client.getInputStream(), upstream.getOutputStream(), false, cfg, burst);
            Relay s2c = new Relay(upstream.getInputStream(), client.getOutputStream(), true, cfg, burst);
            c2s.start(); s2c.start();
            c2s.join(); s2c.join();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> Nota de implementação: separar o `BurstQueue` por direção (hold de incoming e outgoing com filas/timers próprios). O esqueleto acima usa um só para simplicidade; no código final usar dois.

### B.6 — Testador manual (padrão do repo: `src/test/java/bugfixingtools/`)

**Create:** `src/test/java/bugfixingtools/ProxyTester.java`

Não há JUnit no projeto; os testes são `main()` standalone. O `ProxyTester`:
1. Sobe um "servidor fake" local que ecoa e imprime com timestamp os bytes recebidos.
2. Sobe o `ProxyServer` apontando para o servidor fake, com `holdOutgoing = { MOVE (62) }` e `burstIntervalMs = 3000`.
3. Um "cliente fake" envia frames `[size][type=62][payload]` e `[size][type=31][payload]` intercalados.
4. **Validação manual:** o servidor fake deve imprimir o frame tipo 31 imediatamente e os frames tipo 62 todos juntos ~3s depois.

### B.7 — Redirecionar o cliente para o proxy (ponto de decisão)

O cliente ROTMG precisa conectar ao proxy (127.0.0.1:2050) em vez do servidor real. Opções, por ordem de esforço:

- **(a) Front-end (tomato/potato) já sabe o IP do servidor** (ele snifa). Adicionar um "modo proxy" no GUI que: preenche `upstreamHost` com o IP observado e redireciona/relança o cliente para 127.0.0.1.
- **(b) Entrada no `hosts`** apontando o hostname do servidor para 127.0.0.1 + proxy com `upstreamHost` fixo.
- **(c) WinDivert/WFP** (transparente, sem mexer no cliente) — esforço alto, fica para depois.

---

## Riscos, tradeoffs e perguntas abertas

1. **ToS / ban.** Segurar/liberar pacotes em rajada manipula o estado do jogo (ex.: MOVE atrasado = "teleport/desync"; burst de shoot = dano concentrado). Isso viola os termos do ROTMG e pode levar a banimento de conta. Feito para estudo/uso próprio, por sua conta e risco.
2. **TCP flow-control.** Segurar muitos bytes pode travar a janela de envio do cliente (ele para de enviar até o ACK). Para rajadas de poucos segundos e pacotes pequenos (MOVE/PLAYERSHOOT), ok.
3. **Direção e desync de timing.** Segurar NEWTICK/MOVE de entrada quebra a lógica de `TickAligner` do sniffer (irrelevante para o proxy, que não re-sincroniza), mas o cliente pode dessincronizar visualmente.
4. **Pergunta aberta:** qual(is) tipo(s) de pacote o usuário quer segurar, e em qual direção? (MOVE=62 outgoing é o caso clássico.) Isso define o default do `ProxyConfig`.
5. **Pergunta aberta:** o hold/burst deve valer para incoming, outgoing, ou ambos?
6. **Redirecionamento:** confirmar se o GUI tomato/potato expõe o IP do game server (para preencher `upstreamHost` automaticamente).
7. **Bytes crus no log:** decidir se o `PacketLogEntry.raw` guarda payload criptografado (wire) ou descriptografado (pós-`decrypt`). Sugestão: descriptografado.

## Ordem de implementação sugerida

1. Parte A (logging) — independente, baixo risco, entrega valor imediato. Tasks A.1→A.5.
2. Parte B (proxy) — tasks B.1→B.6, com `ProxyTester` validando hold/burst sem jogo real.
3. Integração com GUI (A.5 + B.7a) na branch tomato/potato.

## Verificação

- Build: `./gradlew build` (Java 8).
- Jar: `./gradlew shadowJar` → `build/libs/RealmShark-v1.2.3.jar`.
- Logging: rodar o sniffer contra o jogo e checar `FullPacketLogger.getRecent(n)` com tipos known + unknown.
- Proxy: rodar `ProxyTester` e confirmar o burst (delay visível no timestamp do servidor fake).
