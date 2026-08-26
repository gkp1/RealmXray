# RealmShark — Hold/Burst de pacotes: ideias ranqueadas

> Documento de decisão. Nenhum código foi criado a partir daqui — só planejamento.

Confirmado no repo do ProxyBridge (`Windows/README.md`, seção "How It Works"): ele usa **WinDivert no kernel**, filtrando por **processo** (resolve nome do processo → PID(s), injeta isso no filtro do WinDivert), e redireciona os pacotes casados para uma **porta de relay local** — o relay (userspace, o próprio ProxyBridge) decide o que fazer com o tráfego antes de reencaminhar. No caso deles: fala SOCKS5/HTTP com o InterceptSuite/Burp. **No nosso caso, o relay seria o nosso próprio código de hold/burst**, falando diretamente com o servidor real do ROTMG — sem nenhuma tradução de protocolo. Isso é exatamente a "Opção 1" abaixo, e a lógica "controlar por nome de processo" é 100% reaproveitável.

---

## Restrição que não muda em nenhuma opção

- TCP entrega bytes **em ordem**. "Segurar o tipo X e deixar Y/Z passarem" = reordenar a stream. Só quem termina a conexão TCP (um proxy/endpoint) consegue reordenar; quem só observa/dropa pacotes na camada de rede não consegue — dropar o segmento do X trava tudo que vem depois até ele chegar (buffer de reassembly do TCP do lado servidor).
- RC4 é cifra de fluxo posicional. Reordenar exige **decrypt + re-encrypt** de cada frame (sabemos a chave — `RotMGRC4Keys` já está no repo).
- Conclusão: **hold seletivo real (não "congela tudo") exige ser um proxy TCP** (opções 1 e 2). WinDivert sozinho (opção 3) não reordena — só congela/derruba/edita.
- Nenhuma opção injeta mod no processo do jogo. Todas atuam fora dele (rede ou processo separado).

---

## Ranking

### 1️⃣ Proxy TCP local + redirecionamento transparente via WinDivert por processo — MELHOR
**Por quê:** invisível pro jogo (não precisa hosts, não precisa saber IP do servidor na hora de configurar, não precisa reconfigurar o launcher). É o modelo do próprio ProxyBridge: WinDivert filtra por nome do processo do jogo e redireciona a flow inteira pro nosso relay local, que já teria a lógica de hold/burst pronta da Opção 2.
**Esforço:** médio-alto (WinDivert.sys/dll via JNA + regra de redirect + relay).
**Manutenibilidade:** boa — a parte de rede (WinDivert) é isolada da lógica de protocolo (hold/burst), que é 100% Java puro e testável sem WinDivert.

### 2️⃣ Proxy TCP local explícito (sem WinDivert, IP fixo em hosts/config) — MELHOR PRA COMEÇAR
**Por quê:** é o núcleo da Opção 1, sem a complexidade de kernel driver. 100% Java, roda em qualquer JDK 8, testável com `bugfixingtools`-style tester, sem admin. É o caminho natural de implementação: valida a lógica de hold/burst/hotkey primeiro, depois pluga o redirect transparente por cima.
**Esforço:** médio.
**Manutenibilidade:** ótima — nenhuma dependência nativa nova.

### 3️⃣ WinDivert puro como firewall (drop/reinject, sem proxy) — LIMITADO
**Por quê:** só WinDivert, sem endpoint TCP. Só entrega um subconjunto: congelar a stream inteira e soltar tudo (lag switch), bloquear um tipo pra sempre, ou editar campo de tamanho igual in-place. **Não faz** "segura o tipo X, deixa Y/Z passarem" (isso é reorder — impossível nessa camada).
**Esforço:** baixo-médio.
**Manutenibilidade:** boa, mas entrega menos do que você pediu.

### 4️⃣ Edição in-place via WinDivert (sem proxy) — MENOS INTERESSANTE
**Por quê:** decrypt→edita campo→re-encrypt→recalcula checksum IP/TCP, tudo na camada de pacote. Frágil (RC4 é posicional, qualquer split de segmento quebra a lógica), não resolve hold/burst.
**Esforço:** alto. **Benefício:** baixo pro seu objetivo. Descartar.

---

## Estrutura de implementação — Opção 2 (base: proxy local puro)

Novo subpacote, sem tocar no pipeline de sniffing existente (`packets.packetcapture.sniff.*` continua intocado — o proxy é um **subsistema paralelo**, não substitui o sniffer):

```
src/main/java/packets/packetcapture/proxy/
├── ProxyConfig.java       # host/porta de escuta, IP:porta do servidor real,
│                          #   sets de "hold" (tipo→direção), intervalo de burst, hotkey
├── FrameSplitter.java     # adaptação do ROTMGPacketConstructor: fatia bytes em
│                          #   frames [size][type][payload] SEM decrypt
├── ProxyCipherPair.java   # um par RC4 (incoming/outgoing) igual ao PacketConstructor,
│                          #   pra decrypt+re-encrypt na hora de reordenar
├── HoldQueue.java         # fila por (direção,tipo): guarda frames decriptados;
│                          #   flush por timer OU por hotkey (Consumer<List<byte[]>>)
├── Relay.java             # 1 thread por direção: lê socket, fatia frames,
│                          #   decrypt, decide hold vs pass-through, re-encrypt na saída
├── ProxyServer.java       # aceita conexão do cliente, abre socket pro servidor real,
│                          #   liga os 2 Relay + HotkeyListener
└── HotkeyListener.java    # thread simples ouvindo um sinal (JNativeHook ou polling
                           #   de arquivo/socket local) pra disparar flush "on demand"
```

**Fluxo:** cliente conecta em `127.0.0.1:2050` (apontado via `hosts` ou config do launcher do tomato/potato) → `ProxyServer` abre conexão real pro servidor do jogo → cada `Relay` decripta o frame recebido, olha o `type` no header, decide: pass-through (re-encripta e envia na hora) ou hold (guarda decriptado na fila) → `HoldQueue` libera a fila por timer (X segundos) ou hotkey, re-encriptando cada frame **na ordem de liberação** (a posição do RC4 avança conforme os frames saem, então a ordem de saída = ordem de re-encrypt).

**Ponto de atenção herdado do plano anterior:** diferente do rascunho inicial (que só reencaminhava o ciphertext original), aqui **precisa decrypt+re-encrypt de fato**, porque a posição no keystream muda quando um frame é adiado. Isso é decisão de design que só se aplica no hold seletivo — se fosse só "congela tudo e solta na mesma ordem", o ciphertext original serviria.

**Teste:** `src/test/java/bugfixingtools/ProxyTester.java` (padrão do repo — sem JUnit): servidor fake + cliente fake trocando frames sintéticos, validando visualmente que o tipo em hold chega atrasado e em rajada, e os outros tipos chegam na hora.

---

## Estrutura de implementação — Opção 1 (Opção 2 + WinDivert transparente)

Tudo da Opção 2 continua igual (o `proxy/` package não muda). Adiciona uma camada de redirecionamento por cima:

```
src/main/java/packets/packetcapture/proxy/windivert/
├── WinDivertBridge.java    # binding JNA pra WinDivert.dll (Open/Recv/Send/Close),
│                           #   reaproveitando o padrão já usado em
│                           #   packets.packetcapture.sniff.ardikars.NativeBridge
├── ProcessResolver.java    # nome do processo do jogo → PID(s) (via WinDivert FLOW
│                           #   layer, que já entrega processId por conexão — é o mesmo
│                           #   truque que o ProxyBridge usa)
└── RedirectRule.java       # monta o filtro WinDivert (ex: "tcp.DstPort == 2050 and
                            #   processId == X") e reescreve dst IP:porta do pacote
                            #   pra 127.0.0.1:<porta do ProxyServer>, e vice-versa na volta
```

**Fluxo adicional:** `WinDivertBridge` abre um handle na camada NETWORK com filtro por PID do processo do jogo + porta 2050 → pacotes SYN casados têm o destino reescrito pra `127.0.0.1:<porta do proxy>` (mantendo IP/porta original guardado numa tabela de tradução, como o ProxyBridge faz) → o `ProxyServer` da Opção 2 recebe a conexão normalmente, mas connecta no **IP real original** (recuperado da tabela) em vez de um valor fixo em config → pacotes de volta do proxy pro jogo têm o source reescrito de volta. Isso elimina a necessidade de mexer em `hosts` ou saber o IP do servidor antecipadamente — o jogo continua "achando" que fala direto com a Trisolar/Beach/etc.

**Dependência nova:** WinDivert.dll + WinDivert.sys (mesmo padrão de distribuição que o Npcap já usa neste projeto — `build.gradle` já tem lógica de seleção de nativos por OS). Precisa de admin pra instalar o driver (igual Npcap hoje).

**Risco extra sobre a Opção 2:** WinDivert exige privilégio elevado e um driver assinado instalado — mais uma peça de instalação pro usuário final, mas replica exatamente o que o Npcap já exige hoje pro sniffer, então não é um requisito novo pro projeto.

---

## Recomendação prática

1. Implementar **Opção 2** primeiro (proxy puro, sem WinDivert) — valida hold/burst + hotkey de verdade com esforço médio e zero dependência nativa nova.
2. Se aprovado, envolver com **Opção 1** (WinDivert redirect por processo) pra ficar transparente — reaproveita 100% do código da Opção 2, só adiciona a camada de redirecionamento.
3. Descartar Opções 3 e 4 — não entregam o hold seletivo que você pediu.

**Decisões pendentes suas antes de eu implementar:**
- Tipo(s) de pacote a segurar e direção (ex.: MOVE=62 outgoing).
- Flush só por timer, só por hotkey, ou ambos.
- Se quer já mirar a Opção 1 direto ou validar a 2 primeiro (recomendo a 2 primeiro).
