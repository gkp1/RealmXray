package packets.packetcapture;

import packets.Packet;
import packets.PacketType;
import packets.incoming.ip.IpAddress;
import packets.packetcapture.encryption.RC4;
import packets.packetcapture.encryption.RotMGRC4Keys;
import packets.packetcapture.logger.PacketLogger;
import packets.packetcapture.logger.FullPacketLogger;
import packets.packetcapture.pconstructor.PacketConstructor;
import packets.packetcapture.register.Register;
import packets.packetcapture.sniff.PProcessor;
import packets.packetcapture.sniff.Sniffer;
import packets.reader.BufferReader;
import packets.packetcapture.sniff.gui.MissingNpcapGUI;
import util.DiagnosticLog;
import util.Util;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The core class to process packets. First the network tap is sniffed to receive all packets. The packets
 * are filtered for port 2050, the rotmg port, and TCP packets. Then the packets are stitched together in
 * streamConstructor and rotmgConstructor class. After the packets are constructed the RC4 cipher is used
 * decrypt the data. The data is then matched with target classes and emitted through the registry.
 */
public class PacketProcessor extends Thread implements PProcessor {
    private final PacketConstructor incomingPacketConstructor;
    private final PacketConstructor outgoingPacketConstructor;
    private final Sniffer sniffer;
    private final PacketLogger logger;
    private final byte[] srcAddr;

    /**
     * Basic constructor of packetProcessor
     * TODO: Add linux and mac support later
     */
    public PacketProcessor() {
        sniffer = new Sniffer(this);
        incomingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.INCOMING_STRING), true);
        outgoingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.OUTGOING_STRING), false);
        logger = new PacketLogger();
        srcAddr = new byte[4];
    }

    /**
     * Start method for PacketProcessor.
     */
    public void run() {
        tapPackets();
    }

    /**
     * Stop method for PacketProcessor.
     */
    public void stopSniffer() {
        sniffer.closeSniffers();
    }

    /**
     * Method to start the packet sniffer that will send packets back to receivedPackets.
     */
    public void tapPackets() {
        DiagnosticLog.log("PROCESSING_THREAD_START", "thread=" + Thread.currentThread().getName());
        logger.startLogger();
        incomingPacketConstructor.startResets();
        outgoingPacketConstructor.startResets();
        try {
            sniffer.startSniffer();
        } catch (UnsatisfiedLinkError e) {
            DiagnosticLog.log("PROCESSING_THREAD_DIED", "UnsatisfiedLinkError, npcap likely missing", e);
            new MissingNpcapGUI();
        } catch (Exception e) {
            DiagnosticLog.log("PROCESSING_THREAD_DIED", "startSniffer() threw, thread is about to terminate", e);
            e.printStackTrace();
        }
        DiagnosticLog.log("PROCESSING_THREAD_END", "tapPackets() returning, thread=" + Thread.currentThread().getName());
    }

    /**
     * Incoming byte data received from incoming TCP packets.
     *
     * @param data    Incoming byte stream
     * @param srcAddr Source IP of incoming packets.
     */
    @Override
    public void incomingStream(byte[] data, byte[] srcAddr) {
        logger.addIncoming(data.length);
        ipEmitter(srcAddr);
        incomingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * Outgoing byte data received from outgoing TCP packets.
     *
     * @param data Outgoing byte stream
     */
    @Override
    public void outgoingStream(byte[] data, byte[] srcAddr) {
        logger.addOutgoing(data.length);
        outgoingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * Emits IP changes as incoming packet.
     *
     * @param srcIp Source IP of incoming packets.
     */
    private void ipEmitter(byte[] srcIp) {
        for (int i = 0; i < srcAddr.length; i++) {
            if (srcAddr[i] != srcIp[i]) {
                System.arraycopy(srcIp, 0, srcAddr, 0, srcAddr.length);
                IpAddress ipAddress = new IpAddress(srcIp);
                FullPacketLogger.INSTANCE.onFrame(true, PacketType.IP_ADDRESS.getIndex(), srcIp.length, srcIp, ipAddress);
                Register.INSTANCE.emitPacketLogs(ipAddress);
                return;
            }
        }
    }

    /**
     * Completed packets constructed by stream and rotmg constructor returned to packet constructor.
     * Decoded by the cipher and sent back to the processor to be emitted to subscribed users.
     *
     * @param type Constructed packet type.
     * @param size size of the packet.
     * @param data Constructed packet data.
     */
    public void processPackets(int type, int size, ByteBuffer data, boolean incoming) {
        byte[] raw = data.array();

        if (!PacketType.containsKey(type)) {
            System.err.println("Unknown packet type:" + type + " Data:" + Arrays.toString(raw));
            FullPacketLogger.INSTANCE.onFrame(incoming, type, size, raw, null);
            return;
        }

        logger.addPacket(type, size);
        Packet packetType = PacketType.getPacket(type).factory();
        packetType.setData(raw);
        BufferReader pData = new BufferReader(data);

        Packet deserialized = null;
        try {
            packetType.deserialize(pData);
            if (!pData.isBufferFullyParsed()) {
                pData.printError(packetType);
            }
            deserialized = packetType;
        } catch (Exception e) {
            Util.printLogs("Buffer exploded: " + pData.getIndex() + "/" + pData.size());
            DiagnosticLog.log("PACKET_DESERIALIZE_FAILED", "type=" + type + " index=" + pData.getIndex() + "/" + pData.size(), e);
            debugPackets(type, raw);
        }

        FullPacketLogger.INSTANCE.onFrame(incoming, type, size, raw, deserialized);
        if (deserialized != null) {
            Register.INSTANCE.emitPacketLogs(packetType);
        }
    }

    /**
     * Helper for debugging packets
     */
    private void debugPackets(int type, byte[] data) {
        Packet packetType = PacketType.getPacket(type).factory();
        Util.printLogs(PacketType.byOrdinal(type) + " " + packetType);
        Util.printLogs(Arrays.toString(data));
    }

    /**
     * Closes the sniffer for shutdown.
     */
    public void closeSniffer() {
        sniffer.closeSniffers();
    }

    @Override
    public void resetIncoming() {
        incomingPacketConstructor.reset();
    }

    @Override
    public void resetOutgoing() {
        outgoingPacketConstructor.reset();
    }
}
