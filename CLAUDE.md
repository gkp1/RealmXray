# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

RealmShark is a Java library that sniffs network packets for Realm of the Mad God at the kernel level (via libpcap/Npcap), decrypts them, and deserializes them into typed packet objects that consumers can subscribe to. It does not use any game code or assets, does not modify/block/send packets, and cannot handle multiple simultaneous game instances.

This `realmshark` branch contains only the sniffing/decoding library — there is no GUI `main` here. The GUI front-ends ("Tomato" and "Potato") live on separate branches (`tomato`, `potato`) and consume this library as a jar built from this branch (placed in their `/libs` folder).

## Build

- Requires OpenJDK 8 (`sourceCompatibility`/`targetCompatibility` = 1.8). Built with Gradle (wrapper included).
- Build a runnable/fat jar: `./gradlew shadowJar` (or `gradlew.bat shadowJar` on Windows) — outputs to `build/libs/RealmShark-<version>.jar`, including native resources (e.g. dll files).
- `./gradlew build` for a normal build/compile.
- Version string is auto-generated into `src/main/java/realmshark/version/Version.java` by the `generateSources` Gradle task (runs before `compileJava`) from `project.version` in `build.gradle` — never hand-edit `Version.java`.
- To bump the release name/version, edit `applicationName`/`project.version` in `build.gradle`.
- Windows only for now; platform-specific native selection (`lwjglNatives`) happens in `build.gradle` based on OS (mac not yet supported for the sniffer itself).

## Tests

- There is no JUnit suite. `src/test/java/bugfixingtools/` contains standalone Swing debugging utilities with their own `main()` methods (`PacketDisplay`, `PacketRead`, `PacketTester`) used to manually inspect/replay raw packet byte arrays while writing/fixing a packet's `deserialize()` method. Run them directly (e.g. from an IDE) when debugging a specific packet's parsing.

## Architecture

Data flows in one direction, bottom-up, through distinct layers:

1. **Sniff** (`packets.packetcapture.sniff`) — `Sniffer`/`PProcessor`/`ardikars.NativeBridge`/`NativeMappings` wrap the ardikars pcap library to capture raw frames off the network adapter, filtered to TCP traffic on port 2050 (the ROTMG port). `netpackets/` (`EthernetPacket`, `Ip4Packet`, `TcpPacket`) parse the raw frame layers enough to extract the TCP payload and direction (incoming/outgoing) and source IP.
2. **Stream/packet assembly** (`packets.packetcapture.sniff.assembly`, `pconstructor`) — TCP payloads arrive as arbitrary MTU-sized chunks and must be reassembled. `PacketConstructor` feeds raw bytes to `ROTMGPacketConstructor`, which stitches TCP segments back into complete ROTMG packet frames (each frame is `[4-byte size][1-byte type][payload]`). `PacketConstructor.build()` deliberately discards data until it sees a non-max-size (< 1460 byte) packet on startup, since the sniffer can attach mid-stream and the first bytes might not be a real frame header (`firstNonLargePacket` logic).
3. **Decryption** (`packets.packetcapture.encryption`) — each direction (incoming/outgoing) has its own `RC4` cipher instance keyed from `RotMGRC4Keys`. `TickAligner` re-syncs the cipher position using `NEWTICK` packets if the stream desyncs (e.g. sniffer started mid-session).
4. **Dispatch/deserialize** (`PacketProcessor`, `packets.PacketType`, `packets.Packet`) — once a frame is decrypted, `PacketProcessor.processPackets()` looks up the numeric packet type in the `PacketType` enum (maps type id ↔ `Direction` ↔ a `Packet` factory), constructs the concrete `Packet` subclass, and calls its `deserialize(BufferReader)`. Each packet type lives under `packets.incoming.*` or `packets.outgoing.*` and only knows how to read its own fields off a `BufferReader` (a thin wrapper over `ByteBuffer` with ROTMG-specific read helpers — see `packets.reader.BufferReader`).
5. **Subscription/emit** (`packets.packetcapture.register.Register`) — a singleton (`Register.INSTANCE`) that library consumers use to subscribe by concrete `Packet` class (`register(PacketType, IPacketListener)`) or to all packets (`registerAll`). After successful deserialization, `PacketProcessor` emits the packet instance through `Register`, invoking every matching listener synchronously. Unregistering while an emit is in progress is deferred and flushed after the emit completes (see `Register.remove`/`emitting`). Two logging channels feed off this pipeline: `PacketLogger` aggregates byte counts and per-type packet stats, while `FullPacketLogger` (see `packets.packetcapture.logger`) is a per-frame log — a bounded ring buffer with optional JSONL file output — that records every frame reaching `processPackets()` (including unknown packet types, which were previously dropped after stderr), capturing direction, type id/name, frame size, raw bytes, and a gson-serialized JSON of the deserialized packet. `PacketProcessor.processPackets()` takes a direction flag (threaded from `PacketConstructor`) so the logger can tell incoming from outgoing.

Adding a new packet type means: add an entry to the `PacketType` enum with its numeric id, `Direction`, and a `::new` factory reference, then create the `Packet` subclass under `packets.incoming` or `packets.outgoing` implementing `deserialize(BufferReader)` to read exactly the fields in wire order (mismatched read order/type will throw or desync `BufferReader`, surfaced via `pData.isBufferFullyParsed()`/`printError` in `PacketProcessor`).

The `assets` package (`AssetExtractor`, `resextractor/*`, `SpriteJson`, `SpriteFlatBuffer`, `IdToAsset`) is a separate, mostly independent subsystem for extracting/decoding Unity asset data (sprites/textures) — unrelated to the live packet-sniffing pipeline above.

`util.Util` holds shared helpers (e.g. debug logging via `Util.printLogs`).
